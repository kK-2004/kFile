package com.kk.openapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.config.MinioProperties;
import com.kk.config.OssProperties;
import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.service.OpenAppService.MigrationItem;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * rootPath 同步迁移测试：成功迁移（copy→事务落地→删旧对象，统计 moved）、
 * copy 失败整体回退（清理已复制对象、DB 不动、500）、活跃分片跳过、无变化直落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenAppMigrationServiceTest {

    private static final String OLD_KEY = "oss-prefix/开放应用/crm/20260101-abcdef/report.pdf";
    private static final String NEW_KEY = "oss-prefix/crm/2026/20260101-abcdef/report.pdf";

    @Mock private OpenAppService openAppService;
    @Mock private StoredFileRepository storedFileRepository;
    @Mock private StoredFileUploadRepository uploadRepository;
    @Mock private StorageBrowserRegistry registry;
    @Mock private StorageBrowserService ossSvc;

    private OpenAppMigrationService service;
    private OpenApp app;
    private StoredFile file;

    @BeforeEach
    void setUp() {
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setPrefix("minio-prefix");
        OssProperties ossProperties = new OssProperties();
        ossProperties.setPrefix("oss-prefix");
        service = new OpenAppMigrationService(openAppService, storedFileRepository, uploadRepository,
                registry, minioProperties, ossProperties);

        app = new OpenApp();
        app.setId(7L);
        app.setAppName("crm");

        file = new StoredFile();
        file.setId(99L);
        file.setOpenAppId(7L);
        file.setName("report.pdf");
        file.setType(StoredFile.TYPE_FILE);
        file.setStorageSource("oss");
        file.setStorageKey(OLD_KEY);
        file.setParentId(50L);

        when(registry.get("oss")).thenReturn(ossSvc);
        when(storedFileRepository.findByOpenAppId(7L)).thenReturn(List.of(file));
        when(uploadRepository.findByStoredFileId(99L)).thenReturn(Optional.empty());
        when(ossSvc.stat(anyString())).thenReturn(new StorageBrowserService.Entry(
                "report.pdf", 1L, new Date(), OLD_KEY, "application/pdf"));
        // 虚拟树：根(开放应用 id=40) → crm(id=50) → 文件
        StoredFile crm = new StoredFile();
        crm.setId(50L);
        crm.setParentId(40L);
        crm.setName("crm");
        crm.setType(StoredFile.TYPE_FOLDER);
        StoredFile openApps = new StoredFile();
        openApps.setId(40L);
        openApps.setParentId(null);
        openApps.setName("开放应用");
        openApps.setType(StoredFile.TYPE_FOLDER);
        when(storedFileRepository.findById(50L)).thenReturn(Optional.of(crm));
        when(storedFileRepository.findById(40L)).thenReturn(Optional.of(openApps));
        when(storedFileRepository.findById(99L)).thenReturn(Optional.of(file));
    }

    @Test
    void migratesAllFilesToNewRoot() {
        OpenAppMigrationService.MigrationResult result = service.changeRootPath(app, "crm/2026");

        assertThat(result.moved()).isEqualTo(1);
        assertThat(result.skipped()).isZero();

        verify(ossSvc).copy(OLD_KEY, NEW_KEY);
        verify(ossSvc).delete(OLD_KEY);

        ArgumentCaptor<List<MigrationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAppService).applyMigration(eq(7L), eq("crm/2026"), captor.capture(), anyList());
        MigrationItem item = captor.getValue().get(0);
        assertThat(item.fileId()).isEqualTo(99L);
        assertThat(item.newSegments()).containsExactly("crm", "2026");
        assertThat(item.newKey()).isEqualTo(NEW_KEY);
        assertThat(item.objectMove()).isTrue();
    }

    @Test
    void copyFailureRollsBackAndKeepsState() {
        // 两个文件：第一个 copy 成功，第二个失败 → 已复制的第一个新对象被清理，DB 不动
        StoredFile file2 = new StoredFile();
        file2.setId(100L);
        file2.setOpenAppId(7L);
        file2.setName("b.pdf");
        file2.setType(StoredFile.TYPE_FILE);
        file2.setStorageSource("oss");
        file2.setStorageKey("oss-prefix/开放应用/crm/20260101-abcdef/b.pdf");
        file2.setParentId(50L);
        when(storedFileRepository.findByOpenAppId(7L)).thenReturn(List.of(file, file2));
        when(storedFileRepository.findById(100L)).thenReturn(Optional.of(file2));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("copy boom");
            return null;
        }).when(ossSvc).copy(anyString(), anyString());

        assertThatThrownBy(() -> service.changeRootPath(app, "crm/2026"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // DB 未动
        verify(openAppService, never()).applyMigration(anyLong(), any(), anyList(), anyList());
        // 已复制的第一个文件新对象被清理
        verify(ossSvc).delete(NEW_KEY);
    }

    @Test
    void activeMultipartUploadSkipped() {
        StoredFileUpload active = new StoredFileUpload();
        active.setStatus(StoredFileUpload.STATUS_UPLOADING);
        when(uploadRepository.findByStoredFileId(99L)).thenReturn(Optional.of(active));

        OpenAppMigrationService.MigrationResult result = service.changeRootPath(app, "crm/2026");

        assertThat(result.moved()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.skippedFiles()).singleElement().asString().contains("分片上传进行中");
        verify(ossSvc, never()).copy(anyString(), anyString());
        // rootPath 字段仍更新（迁移项为空也走事务落地）
        List<MigrationItem> empty = List.of();
        verify(openAppService).applyMigration(eq(7L), eq("crm/2026"), eq(empty), anyList());
    }

    @Test
    void unchangedRootOnlyPersistsField() {
        OpenAppMigrationService.MigrationResult result = service.changeRootPath(app, "开放应用/crm");

        assertThat(result.moved()).isZero();
        assertThat(result.skipped()).isZero();
        List<MigrationItem> empty = List.of();
        verify(openAppService).applyMigration(eq(7L), eq("开放应用/crm"), eq(empty), anyList());
        verify(ossSvc, never()).copy(anyString(), anyString());
    }

    @Test
    void fileOutsideOldRootSkipped() {
        // 文件不在「开放应用/crm」之下 → 跳过且不迁移
        file.setParentId(null);
        when(storedFileRepository.findById(50L)).thenReturn(Optional.empty());

        OpenAppMigrationService.MigrationResult result = service.changeRootPath(app, "crm/2026");

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.skippedFiles()).singleElement().asString().contains("不在当前根目录下");
    }

    @Test
    void missingSourceObjectMovesDbNodeOnly() {
        when(ossSvc.stat(OLD_KEY)).thenReturn(null);

        OpenAppMigrationService.MigrationResult result = service.changeRootPath(app, "crm/2026");

        assertThat(result.moved()).isEqualTo(1);
        verify(ossSvc, never()).copy(anyString(), anyString());
        verify(ossSvc, never()).delete(anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MigrationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAppService).applyMigration(eq(7L), eq("crm/2026"), captor.capture(), anyList());
        assertThat(captor.getValue().get(0).objectMove()).isFalse();
    }
}
