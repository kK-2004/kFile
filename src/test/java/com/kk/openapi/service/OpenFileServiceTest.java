package com.kk.openapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.kk.common.service.AppConfigService;
import com.kk.config.MinioProperties;
import com.kk.config.OssProperties;
import com.kk.openapi.entity.OpenApp;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.service.MultipartUploadService;
import com.kk.storage.service.StoredFileService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 开放文件 API 业务测试：数据源路由（默认/覆盖/未启用）、上传生命周期（UPLOADING→UPLOADED + openAppId 归属）、
 * 非法 path 拒绝、下载链接 clamp 与 404/越权、分片能力判断。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenFileServiceTest {

    @Mock private StoredFileRepository storedFileRepository;
    @Mock private StorageBrowserRegistry registry;
    @Mock private OpenAppService openAppService;
    @Mock private StoredFileService storedFileService;
    @Mock private AppConfigService appConfigService;
    @Mock private ObjectProvider<MultipartUploadService> multipartProvider;
    @Mock private MultipartUploadService multipartUploadService;
    @Mock private StorageBrowserService ossSvc;
    @Mock private StorageBrowserService minioSvc;

    private OpenFileService service;
    private OpenApp app;

    @BeforeEach
    void setUp() {
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setPrefix("minio-prefix");
        OssProperties ossProperties = new OssProperties();
        ossProperties.setPrefix("oss-prefix");
        service = new OpenFileService(storedFileRepository, registry, openAppService, storedFileService,
                appConfigService, minioProperties, ossProperties, multipartProvider);

        app = new OpenApp();
        app.setId(7L);
        app.setAppName("crm");

        when(ossSvc.sourceId()).thenReturn("oss");
        when(minioSvc.sourceId()).thenReturn("minio");
        when(registry.get("oss")).thenReturn(ossSvc);
        when(registry.get("minio")).thenReturn(minioSvc);
        when(registry.get("bad")).thenThrow(new IllegalArgumentException("未知或未启用的数据源: bad"));
        when(appConfigService.getRaw(anyString())).thenReturn(null);
        when(openAppService.ensureFolderChain(anyList())).thenReturn(42L);
        when(storedFileService.resolveFolderPath(42L)).thenReturn("开放应用/crm/avatars");
        lenient().when(multipartProvider.getIfAvailable()).thenReturn(multipartUploadService);
    }

    // ===== 数据源路由 =====

    @Test
    void sourceFallsBackToConfiguredDefault() {
        when(appConfigService.getRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE)).thenReturn("minio");
        assertThat(service.resolveSource(null)).isEqualTo("minio");
        assertThat(service.resolveSource("")).isEqualTo("minio");
        assertThat(service.resolveSource("  ")).isEqualTo("minio");
    }

    @Test
    void sourceDefaultsToOssWhenUnset() {
        assertThat(service.resolveSource(null)).isEqualTo("oss");
    }

    @Test
    void explicitSourceOverridesDefault() {
        when(appConfigService.getRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE)).thenReturn("minio");
        assertThat(service.resolveSource("oss")).isEqualTo("oss");
    }

    @Test
    void unknownSourceRejected() {
        assertThatThrownBy(() -> service.resolveSource("bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知或未启用");
    }

    // ===== 简单上传 =====

    @Test
    void initUploadCreatesUploadingNodeWithAppOwnership() {
        when(ossSvc.presignedPutUrl(anyString(), anyLong(), anyString())).thenReturn("https://oss/put");
        java.util.concurrent.atomic.AtomicReference<StoredFile> savedRef = new java.util.concurrent.atomic.AtomicReference<>();
        when(storedFileRepository.save(any())).thenAnswer(inv -> {
            StoredFile f = inv.getArgument(0);
            f.setId(99L);
            savedRef.set(f);
            return f;
        });

        OpenFileService.UploadInitResult r =
                service.initUpload(app, "report.pdf", "application/pdf", "avatars", "oss");

        assertThat(r.source()).isEqualTo("oss");
        assertThat(r.putUrl()).isEqualTo("https://oss/put");
        assertThat(r.fileId()).isEqualTo(99L);
        assertThat(r.storageKey()).startsWith("oss-prefix/开放应用/crm/avatars/").endsWith("/report.pdf");
        assertThat(savedRef.get()).isNotNull();
        assertThat(savedRef.get().getOpenAppId()).isEqualTo(7L);
        assertThat(savedRef.get().getStatus()).isEqualTo(StoredFile.STATUS_UPLOADING);
    }

    @Test
    void illegalPathSegmentRejected() {
        assertThatThrownBy(() -> service.initUpload(app, "a.pdf", null, "../etc", "oss"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeUploadBackfillsSizeFromStat() {
        StoredFile f = new StoredFile();
        f.setId(99L);
        f.setOpenAppId(7L);
        f.setName("report.pdf");
        f.setType(StoredFile.TYPE_FILE);
        f.setStatus(StoredFile.STATUS_UPLOADING);
        when(storedFileRepository.findByStorageKeyAndStatus("k1", StoredFile.STATUS_UPLOADING))
                .thenReturn(Optional.of(f));
        when(ossSvc.stat("k1")).thenReturn(new StorageBrowserService.Entry(
                "report.pdf", 1234L, new Date(), "k1", "application/pdf"));

        OpenFileService.UploadCompleteResult r = service.completeUpload(app, "k1", "oss");

        assertThat(r.fileId()).isEqualTo(99L);
        assertThat(r.size()).isEqualTo(1234L);
        assertThat(f.getStatus()).isEqualTo(StoredFile.STATUS_UPLOADED);
    }

    @Test
    void completeUploadRejectedWhenObjectMissing() {
        StoredFile f = new StoredFile();
        f.setId(99L);
        f.setOpenAppId(7L);
        f.setType(StoredFile.TYPE_FILE);
        when(storedFileRepository.findByStorageKeyAndStatus("k1", StoredFile.STATUS_UPLOADING))
                .thenReturn(Optional.of(f));
        when(ossSvc.stat("k1")).thenReturn(null);

        assertThatThrownBy(() -> service.completeUpload(app, "k1", "oss"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对象尚未上传");
    }

    @Test
    void completeUploadRejectedForOtherAppsFile() {
        StoredFile f = new StoredFile();
        f.setId(99L);
        f.setOpenAppId(8L); // 其他应用
        f.setType(StoredFile.TYPE_FILE);
        when(storedFileRepository.findByStorageKeyAndStatus("k1", StoredFile.STATUS_UPLOADING))
                .thenReturn(Optional.of(f));

        assertThatThrownBy(() -> service.completeUpload(app, "k1", "oss"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("其他应用");
    }

    // ===== 分片能力判断 =====

    @Test
    void multipartRejectedForNonMinioSource() {
        assertThatThrownBy(() -> service.multipartInit(app, "big.bin", null, 100L, 20, "md5", null, "oss"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持分片上传");
    }

    @Test
    void multipartRejectedWhenServiceUnavailable() {
        when(multipartProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.multipartInit(app, "big.bin", null, 100L, 20, "md5", null, "minio"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持分片上传");
    }

    @Test
    void multipartInitAttributesStoredFileToApp() {
        MultipartUploadService.InitResult init = new MultipartUploadService.InitResult(
                "uid", "chunkPrefix", "minio-prefix/x", 10, 55L, List.of(), false);
        when(multipartUploadService.init(any(), any(), any(), anyLong(), anyInt(), any(), any()))
                .thenReturn(init);
        StoredFile f = new StoredFile();
        f.setId(55L);
        when(storedFileRepository.findById(55L)).thenReturn(Optional.of(f));

        MultipartUploadService.InitResult r = service.multipartInit(app, "big.bin", null, 100L, 10, "md5", null, "minio");

        assertThat(r.storedFileId).isEqualTo(55L);
        assertThat(f.getOpenAppId()).isEqualTo(7L);
    }

    // ===== 下载链接 =====

    @Test
    void downloadLinkByFileId() {
        StoredFile f = new StoredFile();
        f.setId(99L);
        f.setOpenAppId(7L);
        f.setType(StoredFile.TYPE_FILE);
        f.setStorageSource("oss");
        f.setStorageKey("k1");
        f.setOriginalName("report.pdf");
        when(storedFileRepository.findById(99L)).thenReturn(Optional.of(f));
        when(ossSvc.downloadUrl("k1", true, 300, "report.pdf")).thenReturn("https://dl");

        OpenFileService.DownloadLinkResult r = service.downloadLink(app, 99L, null, null, null, null);
        assertThat(r.url()).isEqualTo("https://dl");
        assertThat(r.expireSeconds()).isEqualTo(300);
    }

    @Test
    void downloadLinkExpiresInClamped() {
        StoredFile f = ownedFile();
        when(storedFileRepository.findById(99L)).thenReturn(Optional.of(f));
        when(ossSvc.downloadUrl(anyString(), anyBoolean(), anyLong(), any())).thenReturn("https://dl");

        assertThat(service.downloadLink(app, 99L, null, null, null, 99999L).expireSeconds()).isEqualTo(3600);
        assertThat(service.downloadLink(app, 99L, null, null, null, 1L).expireSeconds()).isEqualTo(60);
    }

    @Test
    void downloadLinkByStorageKeyRequiresOwnership() {
        StoredFile f = ownedFile();
        when(storedFileRepository.findFirstByStorageKeyOrderByIdDesc("k1")).thenReturn(Optional.of(f));
        when(ossSvc.downloadUrl(anyString(), anyBoolean(), anyLong(), any())).thenReturn("https://dl");

        assertThat(service.downloadLink(app, null, "k1", "oss", null, null).url()).isEqualTo("https://dl");

        f.setOpenAppId(8L); // 其他应用的文件 → 404 不泄露存在性
        assertThatThrownBy(() -> service.downloadLink(app, null, "k1", "oss", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadLinkNotFound() {
        when(storedFileRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.downloadLink(app, 1L, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.downloadLink(app, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StoredFile ownedFile() {
        StoredFile f = new StoredFile();
        f.setId(99L);
        f.setOpenAppId(7L);
        f.setType(StoredFile.TYPE_FILE);
        f.setStorageSource("oss");
        f.setStorageKey("k1");
        f.setOriginalName("report.pdf");
        return f;
    }
}
