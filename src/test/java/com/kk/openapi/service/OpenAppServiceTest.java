package com.kk.openapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.repo.OpenAppRepository;
import com.kk.openapi.service.OpenAppService.MigrationItem;
import com.kk.security.oauth.OAuthCrypto;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import com.kk.storage.service.MultipartUploadService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放应用凭证服务测试：创建（kapp_ 前缀 + 哈希落库 + 明文仅返回一次）、重名 409、
 * appName/rootPath 校验、轮换、启停、ensureFolderChain 幂等、applyMigration 落地与空目录清理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenAppServiceTest {

    @Mock private OpenAppRepository openAppRepository;
    @Mock private StoredFileRepository storedFileRepository;
    @Mock private StoredFileUploadRepository uploadRepository;
    @Mock private StorageBrowserRegistry registry;
    @Mock private ObjectProvider<MultipartUploadService> multipartProvider;
    @Mock private MultipartUploadService multipartUploadService;
    @Mock private StorageBrowserService ossSvc;
    @Spy private final OAuthCrypto crypto = new OAuthCrypto();

    @InjectMocks
    private OpenAppService service;

    @Test
    void createGeneratesOneTimeTokenWithHashStored() {
        when(openAppRepository.existsByAppName("crm")).thenReturn(false);
        when(openAppRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OpenAppService.CreatedApp created = service.create("crm", "客户系统", "crm/2026");

        assertThat(created.token()).startsWith("kfile_").hasSizeGreaterThan(20);
        assertThat(created.app().getTokenHash()).isEqualTo(crypto.sha256Hex(created.token()));
        assertThat(created.app().getRootPath()).isEqualTo("crm/2026");
        assertThat(created.app().isEnabled()).isTrue();
    }

    @Test
    void createDuplicateNameRejectedWith409() {
        when(openAppRepository.existsByAppName("crm")).thenReturn(true);
        assertThatThrownBy(() -> service.create("crm", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(openAppRepository, never()).save(any());
    }

    @Test
    void createRejectsAppNameWithPathSeparator() {
        assertThatThrownBy(() -> service.create("a/b", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeRootPathValidatesSegments() {
        assertThat(OpenAppService.normalizeRootPath(null)).isNull();
        assertThat(OpenAppService.normalizeRootPath("  ")).isNull();
        assertThat(OpenAppService.normalizeRootPath("crm/2026")).isEqualTo("crm/2026");
        assertThatThrownBy(() -> OpenAppService.normalizeRootPath("a/../b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAppService.normalizeRootPath("a//b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空路径段");
    }

    @Test
    void rotateReplacesHashImmediately() {
        OpenApp app = new OpenApp();
        app.setId(7L);
        app.setAppName("crm");
        String oldHash = crypto.sha256Hex("kapp_old");
        app.setTokenHash(oldHash);
        when(openAppRepository.findById(7L)).thenReturn(Optional.of(app));
        when(openAppRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String token = service.rotate(7L);

        assertThat(token).startsWith("kfile_");
        assertThat(app.getTokenHash()).isEqualTo(crypto.sha256Hex(token)).isNotEqualTo(oldHash);
    }

    @Test
    void setEnabledToggles() {
        OpenApp app = new OpenApp();
        app.setId(7L);
        app.setEnabled(true);
        when(openAppRepository.findById(7L)).thenReturn(Optional.of(app));
        when(openAppRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setEnabled(7L, false);
        assertThat(app.isEnabled()).isFalse();
    }

    @Test
    void effectiveRootDefaultsToOpenAppsFolder() {
        OpenApp app = new OpenApp();
        app.setAppName("crm");
        assertThat(OpenAppService.effectiveRoot(app)).isEqualTo("开放应用/crm");
        app.setRootPath("crm/2026");
        assertThat(OpenAppService.effectiveRoot(app)).isEqualTo("crm/2026");
    }

    @Test
    void ensureFolderChainCreatesMissingFoldersIdempotently() {
        // 根下已有「开放应用」(id=40)，其下无「crm」
        StoredFile root = folder(40L, null, "开放应用");
        when(storedFileRepository.findByParentIdIsNullAndNameAndType("开放应用", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.of(root));
        when(storedFileRepository.findByParentIdAndNameAndType(40L, "crm", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.empty());
        StoredFile crm = folder(41L, 40L, "crm");
        when(storedFileRepository.save(any())).thenAnswer(inv -> {
            StoredFile f = inv.getArgument(0);
            f.setId(41L);
            return crm;
        });

        Long parentId = service.ensureFolderChain(List.of("开放应用", "crm"));

        assertThat(parentId).isEqualTo(41L);
    }

    @Test
    void applyMigrationReparentsFilesAndCleansEmptyOldTree() {
        OpenApp app = new OpenApp();
        app.setId(7L);
        app.setAppName("crm");
        when(openAppRepository.findById(7L)).thenReturn(Optional.of(app));
        when(openAppRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoredFile file = new StoredFile();
        file.setId(99L);
        when(storedFileRepository.findById(99L)).thenReturn(Optional.of(file));
        // 新目录链直接命中已有节点
        when(storedFileRepository.findByParentIdIsNullAndNameAndType("crm", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.of(folder(60L, null, "crm")));
        when(storedFileRepository.findByParentIdAndNameAndType(60L, "2026", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.of(folder(61L, 60L, "2026")));
        when(storedFileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 旧根节点（开放应用 id=40）及其下已无子项 → 删除；有子项则保留
        StoredFile oldRoot = folder(40L, null, "开放应用");
        when(storedFileRepository.findByParentIdIsNullAndNameAndType("开放应用", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.of(oldRoot));
        when(storedFileRepository.findByParentId(40L)).thenReturn(List.of());
        when(storedFileRepository.findById(40L)).thenReturn(Optional.of(oldRoot));

        service.applyMigration(7L, "crm/2026",
                List.of(new MigrationItem(99L, List.of("crm", "2026"), "oldKey", "newKey", true)),
                List.of("开放应用", "crm"));

        assertThat(file.getParentId()).isEqualTo(61L);
        assertThat(file.getStorageKey()).isEqualTo("newKey");
        assertThat(app.getRootPath()).isEqualTo("crm/2026");
        verify(storedFileRepository).delete(oldRoot);
    }

    @Test
    void applyMigrationKeepsNonEmptyOldFolders() {
        OpenApp app = new OpenApp();
        app.setId(7L);
        when(openAppRepository.findById(7L)).thenReturn(Optional.of(app));
        when(openAppRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storedFileRepository.findByParentIdIsNullAndNameAndType("开放应用", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.of(folder(40L, null, "开放应用")));
        // 旧根下仍有一个文件（如被跳过的活跃分片）→ 不删除
        StoredFile remaining = new StoredFile();
        remaining.setId(100L);
        when(storedFileRepository.findByParentId(40L)).thenReturn(List.of(remaining));

        service.applyMigration(7L, "crm/2026", List.of(), List.of("开放应用"));

        verify(storedFileRepository, never()).delete(any(StoredFile.class));
    }

    // ===== 删除（级联清理） =====

    private OpenApp crmApp() {
        OpenApp app = new OpenApp();
        app.setId(7L);
        app.setAppName("crm");
        return app;
    }

    private StoredFile appFile(Long id, String key) {
        StoredFile f = new StoredFile();
        f.setId(id);
        f.setOpenAppId(7L);
        f.setName("f" + id + ".pdf");
        f.setType(StoredFile.TYPE_FILE);
        f.setStorageSource("oss");
        f.setStorageKey(key);
        return f;
    }

    @Test
    void deleteAppCascadesFilesRecordsAndApp() {
        OpenApp app = crmApp();
        StoredFile f1 = appFile(99L, "oss-prefix/开放应用/crm/a.pdf");
        StoredFile f2 = appFile(100L, "oss-prefix/开放应用/crm/b.pdf");
        when(openAppRepository.findById(7L)).thenReturn(Optional.of(app));
        when(storedFileRepository.findByOpenAppId(7L)).thenReturn(List.of(f1, f2));
        when(registry.get("oss")).thenReturn(ossSvc);
        when(uploadRepository.findByStoredFileId(any())).thenReturn(Optional.empty());
        // 根路径目录链：开放应用(40) → crm(50)，删除文件后已空
        when(storedFileRepository.findByParentIdIsNullAndNameAndType("开放应用", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.of(folder(40L, null, "开放应用")));
        when(storedFileRepository.findByParentIdAndNameAndType(40L, "crm", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.of(folder(50L, 40L, "crm")));
        when(storedFileRepository.findByParentId(50L)).thenReturn(List.of());
        when(storedFileRepository.findById(50L)).thenReturn(Optional.of(folder(50L, 40L, "crm")));

        OpenAppService.DeleteResult result = service.deleteApp(7L);

        assertThat(result.deletedFiles()).isEqualTo(2);
        assertThat(result.failedObjects()).isZero();
        verify(ossSvc).delete("oss-prefix/开放应用/crm/a.pdf");
        verify(ossSvc).delete("oss-prefix/开放应用/crm/b.pdf");
        verify(storedFileRepository).delete(f1);
        verify(storedFileRepository).delete(f2);
        // 搬空目录清理：crm 目录节点被删除（开放应用根目录保留）
        StoredFile crmFolder = storedFileRepository.findById(50L).orElseThrow();
        verify(storedFileRepository).delete(crmFolder);
        verify(openAppRepository).delete(app);
    }

    @Test
    void deleteAppKeepsSharedFoldersAndCountsFailedObjects() {
        OpenApp app = crmApp();
        StoredFile f1 = appFile(99L, "k1");
        when(openAppRepository.findById(7L)).thenReturn(Optional.of(app));
        when(storedFileRepository.findByOpenAppId(7L)).thenReturn(List.of(f1));
        when(registry.get("oss")).thenReturn(ossSvc);
        doThrow(new IllegalStateException("oss down")).when(ossSvc).delete("k1");
        when(uploadRepository.findByStoredFileId(any())).thenReturn(Optional.empty());
        // 目录链：根下未建「开放应用」→ 无目录清理
        when(storedFileRepository.findByParentIdIsNullAndNameAndType("开放应用", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.empty());

        OpenAppService.DeleteResult result = service.deleteApp(7L);

        // 对象删除失败不阻断：节点与记录仍删除，计入 failedObjects
        assertThat(result.failedObjects()).isEqualTo(1);
        verify(storedFileRepository).delete(f1);
        verify(openAppRepository).delete(app);
    }

    @Test
    void deleteAppAbortsActiveMultipartInsteadOfPlainDelete() {
        OpenApp app = crmApp();
        StoredFile f1 = appFile(99L, "minio-prefix/开放应用/crm/big.bin");
        StoredFileUpload active = new StoredFileUpload();
        active.setContentMd5("md5-1");
        active.setStatus(StoredFileUpload.STATUS_UPLOADING);
        when(openAppRepository.findById(7L)).thenReturn(Optional.of(app));
        when(storedFileRepository.findByOpenAppId(7L)).thenReturn(List.of(f1));
        when(uploadRepository.findByStoredFileId(99L)).thenReturn(Optional.of(active));
        when(multipartProvider.getIfAvailable()).thenReturn(multipartUploadService);
        when(storedFileRepository.findByParentIdIsNullAndNameAndType("开放应用", StoredFile.TYPE_FOLDER))
                .thenReturn(Optional.empty());

        OpenAppService.DeleteResult result = service.deleteApp(7L);

        assertThat(result.deletedFiles()).isEqualTo(1);
        // 活跃分片走 abort（内部删记录+节点+服务端 abort），不再单独删对象/节点
        verify(multipartUploadService).abort("md5-1");
        verify(storedFileRepository, never()).delete(f1);
        verify(openAppRepository).delete(app);
    }

    private StoredFile folder(Long id, Long parentId, String name) {
        StoredFile f = new StoredFile();
        f.setId(id);
        f.setParentId(parentId);
        f.setName(name);
        f.setType(StoredFile.TYPE_FOLDER);
        return f;
    }
}
