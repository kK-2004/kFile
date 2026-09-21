package com.kk.openapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.common.service.AppConfigService;
import com.kk.config.MinioProperties;
import com.kk.config.OssProperties;
import com.kk.openapi.entity.OpenApp;
import com.kk.project.repo.SubmissionRepository;
import com.kk.security.repo.AdminUserRepository;
import com.kk.share.service.ShareLinkService;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import com.kk.storage.service.MultipartUploadService;
import com.kk.storage.service.StoredFileService;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
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
 * 开放应用批量删除测试：整批预校验（404 统一不泄露存在性 / 上传中 409 / 非法列表 400）无删除副作用，
 * 校验通过后清理对象、上传记录与 DB 节点并计数；对象删除失败尽力而为。
 * 挂接真实 {@link StoredFileService}，断言落到对象/DB 删除调用本身。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenFileBatchDeleteServiceTest {

    @Mock private StoredFileRepository storedFileRepository;
    @Mock private StoredFileUploadRepository uploadRepository;
    @Mock private StorageBrowserRegistry registry;
    @Mock private ShareLinkService shareLinkService;
    @Mock private AdminUserRepository adminUserRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private AppConfigService appConfigService;
    @Mock private OpenAppService openAppService;
    @Mock private ObjectProvider<MultipartUploadService> multipartProvider;
    @Mock private StorageBrowserService ossSvc;

    private OpenFileService service;
    private OpenApp app;

    @BeforeEach
    void setUp() {
        MinioProperties minioProperties = new MinioProperties();
        OssProperties ossProperties = new OssProperties();
        StoredFileService storedFileService = new StoredFileService(storedFileRepository, uploadRepository,
                registry, shareLinkService, minioProperties, ossProperties, adminUserRepository,
                submissionRepository, appConfigService);
        service = new OpenFileService(storedFileRepository, registry, openAppService, storedFileService,
                minioProperties, ossProperties, multipartProvider);

        app = new OpenApp();
        app.setId(7L);
        app.setAppName("crm");

        when(ossSvc.sourceId()).thenReturn("oss");
        when(registry.get("oss")).thenReturn(ossSvc);
        when(uploadRepository.findByStoredFileId(any())).thenReturn(Optional.empty());
    }

    private StoredFile ownedFile(long id, String key) {
        StoredFile f = new StoredFile();
        f.setId(id);
        f.setOpenAppId(7L);
        f.setName("f" + id);
        f.setType(StoredFile.TYPE_FILE);
        f.setStatus(StoredFile.STATUS_UPLOADED);
        f.setStorageSource("oss");
        f.setStorageKey(key);
        return f;
    }

    private void assertNoDeleteSideEffects() {
        verify(ossSvc, never()).delete(anyString());
        verify(storedFileRepository, never()).delete(any(StoredFile.class));
        verify(uploadRepository, never()).delete(any(StoredFileUpload.class));
    }

    // ===== 成功批次 =====

    @Test
    void deletesObjectsUploadRecordsAndDbRowsForOwnedUploadedFiles() {
        StoredFile f1 = ownedFile(1, "k1");
        StoredFile f2 = ownedFile(2, "k2");
        StoredFileUpload upload1 = new StoredFileUpload();
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(f1, f2));
        when(uploadRepository.findByStoredFileId(1L)).thenReturn(Optional.of(upload1));

        OpenFileService.BatchDeleteResult result = service.deleteFiles(app, List.of(2L, 1L));

        assertThat(result.deletedFiles()).isEqualTo(2);
        assertThat(result.failedObjects()).isZero();
        verify(ossSvc).delete("k1");
        verify(ossSvc).delete("k2");
        verify(uploadRepository).delete(upload1); // 关联分片上传记录一并清理
        verify(storedFileRepository).delete(f1);
        verify(storedFileRepository).delete(f2);
    }

    @Test
    void idsLockedInAscendingOrderRegardlessOfRequestOrder() {
        StoredFile f1 = ownedFile(1, "k1");
        StoredFile f3 = ownedFile(3, "k3");
        StoredFile f5 = ownedFile(5, "k5");
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(f1, f3, f5));

        service.deleteFiles(app, List.of(5L, 1L, 3L));

        verify(storedFileRepository).findAllByIdInForUpdate(List.of(1L, 3L, 5L));
    }

    @Test
    void objectDeleteFailureStillDeletesRecordsAndCounts() {
        StoredFile f1 = ownedFile(1, "k1");
        StoredFile f2 = ownedFile(2, "k2");
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(f1, f2));
        doThrow(new RuntimeException("oss down")).when(ossSvc).delete("k2");

        OpenFileService.BatchDeleteResult result = service.deleteFiles(app, List.of(1L, 2L));

        assertThat(result.deletedFiles()).isEqualTo(2);
        assertThat(result.failedObjects()).isEqualTo(1);
        verify(storedFileRepository).delete(f1);
        verify(storedFileRepository).delete(f2); // 失败仍删 DB 行（管理端尽力而为语义）
    }

    // ===== 预校验失败：统一 404，无任何删除副作用 =====

    @Test
    void crossAppFileRejectedAs404WithoutSideEffects() {
        StoredFile mine = ownedFile(1, "k1");
        StoredFile foreign = ownedFile(2, "k2");
        foreign.setOpenAppId(8L);
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(mine, foreign));

        assertThatThrownBy(() -> service.deleteFiles(app, List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertNoDeleteSideEffects();
    }

    @Test
    void adminUploadedFileRejectedAs404WithoutSideEffects() {
        StoredFile mine = ownedFile(1, "k1");
        StoredFile adminFile = ownedFile(2, "k2");
        adminFile.setOpenAppId(null); // 管理员后台上传
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(mine, adminFile));

        assertThatThrownBy(() -> service.deleteFiles(app, List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertNoDeleteSideEffects();
    }

    @Test
    void missingIdRejectedAs404WithoutSideEffects() {
        StoredFile f1 = ownedFile(1, "k1");
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(f1)); // id=2 缺失

        assertThatThrownBy(() -> service.deleteFiles(app, List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertNoDeleteSideEffects();
    }

    @Test
    void folderIdRejectedAs404WithoutSideEffects() {
        StoredFile mine = ownedFile(1, "k1");
        StoredFile folder = ownedFile(2, null);
        folder.setType(StoredFile.TYPE_FOLDER);
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(mine, folder));

        assertThatThrownBy(() -> service.deleteFiles(app, List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertNoDeleteSideEffects(); // 不递归、不误删同批次合法文件
    }

    @Test
    void foreignUploadingFileReports404Not409() {
        // 非本应用节点不得通过状态差异被探知：跨应用 + UPLOADING 仍统一 404
        StoredFile foreign = ownedFile(2, "k2");
        foreign.setOpenAppId(8L);
        foreign.setStatus(StoredFile.STATUS_UPLOADING);
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.deleteFiles(app, List.of(2L)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertNoDeleteSideEffects();
    }

    // ===== 预校验失败：本应用未完成上传 409 =====

    @Test
    void uploadingFileRejectedAs409WithoutSideEffects() {
        StoredFile mine = ownedFile(1, "k1");
        StoredFile uploading = ownedFile(2, "k2");
        uploading.setStatus(StoredFile.STATUS_UPLOADING);
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of(mine, uploading));

        assertThatThrownBy(() -> service.deleteFiles(app, List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertNoDeleteSideEffects();
    }

    // ===== 非法列表：400，不触库 =====

    @Test
    void invalidFileIdsRejectedWithoutQuerying() {
        // Arrays.asList：外层需容纳 null 列表本身（List.of 不允许 null 元素）
        List<List<Long>> invalid = java.util.Arrays.asList(
                null,
                List.of(),
                List.of(1L, 1L),                      // 重复
                java.util.Arrays.asList(1L, null),    // null 元素
                List.of(0L, 1L),                      // 非正数
                List.of(-1L),                         // 负数
                LongStream.rangeClosed(1, 101).boxed().toList()); // 超过 100

        for (List<Long> ids : invalid) {
            assertThatThrownBy(() -> service.deleteFiles(app, ids))
                    .as("非法列表应 400: %s", ids)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(storedFileRepository, never()).findAllByIdInForUpdate(anyList());
        assertNoDeleteSideEffects();
    }

    @Test
    void hundredIdsAccepted() {
        List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
        when(storedFileRepository.findAllByIdInForUpdate(anyList())).thenReturn(List.of());

        // 边界：恰好 100 个通过参数校验进入锁定读取；记录缺失映射 404（预期）
        assertThatThrownBy(() -> service.deleteFiles(app, ids))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(storedFileRepository).findAllByIdInForUpdate(ids);
    }
}
