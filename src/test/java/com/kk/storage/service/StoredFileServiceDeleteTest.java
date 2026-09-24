package com.kk.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.common.service.AppConfigService;
import com.kk.config.MinioProperties;
import com.kk.config.OssProperties;
import com.kk.project.repo.SubmissionRepository;
import com.kk.security.repo.AdminUserRepository;
import com.kk.share.service.ShareLinkService;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 管理端删除与开放 API 共用的单文件清理逻辑回归测试：
 * 文件删除清理对象+上传记录+DB 行（对象失败尽力而为仍删行并计数）、文件夹递归删除、非 owner 拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoredFileServiceDeleteTest {

    @Mock private StoredFileRepository storedFileRepository;
    @Mock private StoredFileUploadRepository uploadRepository;
    @Mock private StorageBrowserRegistry registry;
    @Mock private ShareLinkService shareLinkService;
    @Mock private AdminUserRepository adminUserRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private AppConfigService appConfigService;
    @Mock private StorageBrowserService ossSvc;

    private StoredFileService service;

    @BeforeEach
    void setUp() {
        service = new StoredFileService(storedFileRepository, uploadRepository, registry, shareLinkService,
                new MinioProperties(), new OssProperties(), adminUserRepository,
                submissionRepository, appConfigService);
        when(ossSvc.sourceId()).thenReturn("oss");
        when(registry.get("oss")).thenReturn(ossSvc);
        when(uploadRepository.findByStoredFileId(any())).thenReturn(Optional.empty());
    }

    private StoredFile file(long id, String key, Long uploaderId) {
        StoredFile f = new StoredFile();
        f.setId(id);
        f.setUploaderId(uploaderId);
        f.setName("f" + id);
        f.setType(StoredFile.TYPE_FILE);
        f.setStatus(StoredFile.STATUS_UPLOADED);
        f.setStorageSource("oss");
        f.setStorageKey(key);
        return f;
    }

    private StoredFile folder(long id, Long uploaderId) {
        StoredFile f = new StoredFile();
        f.setId(id);
        f.setUploaderId(uploaderId);
        f.setName("d" + id);
        f.setType(StoredFile.TYPE_FOLDER);
        return f;
    }

    @Test
    void adminDeleteFileCleansObjectUploadRecordAndDbRow() {
        StoredFile f = file(5, "k5", 1L);
        StoredFileUpload upload = new StoredFileUpload();
        when(storedFileRepository.findById(5L)).thenReturn(Optional.of(f));
        when(uploadRepository.findByStoredFileId(5L)).thenReturn(Optional.of(upload));

        StoredFileService.DeleteResult result = service.delete(5L, 1L);

        assertThat(result.deletedDb()).isEqualTo(1);
        assertThat(result.failedObjects()).isZero();
        verify(ossSvc).delete("k5");
        verify(uploadRepository).delete(upload);
        verify(storedFileRepository).delete(f);
    }

    @Test
    void fileManagerUploadUsesInferredPreviewableContentType() {
        when(ossSvc.presignedPutUrl(anyString(), anyLong(), eq("image/png")))
                .thenReturn("https://oss/put");
        when(storedFileRepository.save(any(StoredFile.class))).thenAnswer(invocation -> {
            StoredFile file = invocation.getArgument(0);
            file.setId(8L);
            return file;
        });

        StoredFileService.DirectUploadInit result =
                service.initUpload(null, "oss", "cover.PNG", "application/octet-stream", null, null);

        assertThat(result.contentType()).isEqualTo("image/png");
        verify(ossSvc).presignedPutUrl(anyString(), anyLong(), eq("image/png"));
    }

    @Test
    void adminDeleteFolderRecursesIntoChildren() {
        StoredFile dir = folder(5, 1L);
        StoredFile child = file(6, "k6", 1L);
        when(storedFileRepository.findById(5L)).thenReturn(Optional.of(dir));
        when(storedFileRepository.findByParentId(5L)).thenReturn(List.of(child));

        StoredFileService.DeleteResult result = service.delete(5L, 1L);

        assertThat(result.deletedDb()).isEqualTo(2); // 文件夹 + 子文件
        verify(ossSvc).delete("k6");
        verify(storedFileRepository).delete(child);
        verify(storedFileRepository).delete(dir);
    }

    @Test
    void objectDeleteFailureCountsButStillDeletesRow() {
        StoredFile f = file(5, "k5", 1L);
        when(storedFileRepository.findById(5L)).thenReturn(Optional.of(f));
        doThrow(new RuntimeException("oss down")).when(ossSvc).delete("k5");

        StoredFileService.DeleteResult result = service.delete(5L, 1L);

        assertThat(result.deletedDb()).isEqualTo(1);
        assertThat(result.failedObjects()).isEqualTo(1);
        verify(storedFileRepository).delete(f);
    }

    @Test
    void nonOwnerDeleteRejectedWithoutSideEffects() {
        StoredFile f = file(5, "k5", 1L);
        when(storedFileRepository.findById(5L)).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> service.delete(5L, 2L)) // actor=2 非 owner
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权删除");
        verify(ossSvc, never()).delete(anyString());
        verify(storedFileRepository, never()).delete(any(StoredFile.class));
    }

    @Test
    void deleteLockedFilesReusesSameCleanupForValidatedBatch() {
        StoredFile f1 = file(1, "k1", null);
        StoredFile f2 = file(2, "k2", null);
        doThrow(new RuntimeException("oss down")).when(ossSvc).delete("k2");

        StoredFileService.DeleteResult result = service.deleteLockedFiles(List.of(f1, f2));

        assertThat(result.deletedDb()).isEqualTo(2);
        assertThat(result.failedObjects()).isEqualTo(1);
        verify(storedFileRepository).delete(f1);
        verify(storedFileRepository).delete(f2);
    }
}
