package com.kk.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kk.config.MinioProperties;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import com.kk.storage.repo.MultipartInitLockRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ExtendWith(MockitoExtension.class)
class MultipartUploadServiceTest {

    @Mock private S3Client s3;
    @Mock private S3Presigner presigner;
    @Mock private StoredFileUploadRepository uploadRepository;
    @Mock private MultipartInitLockRepository initLockRepository;
    @Mock private StoredFileRepository storedFileRepository;
    @Mock private StoredFileService storedFileService;

    private MultipartUploadService service;

    @BeforeEach
    void setUp() {
        service = new MultipartUploadService(s3, presigner, new MinioProperties(), uploadRepository, initLockRepository,
                storedFileRepository, storedFileService);
    }

    @Test
    void duplicateCompleteIsIdempotentAndDoesNotCallMinioAgain() {
        StoredFileUpload upload = upload(StoredFileUpload.STATUS_UPLOADED, 2);
        when(uploadRepository.findByContentMd5ForUpdate("md5")).thenReturn(Optional.of(upload));

        MultipartUploadService.CompleteResult result = service.complete("md5", List.of());

        assertThat(result.storageKey()).isEqualTo("files/f.bin");
        assertThat(result.storedFileId()).isEqualTo(42L);
        verifyNoInteractions(s3);
    }

    @Test
    void invalidPartListDoesNotAbortOrDeleteResumableState() {
        StoredFileUpload upload = upload(StoredFileUpload.STATUS_UPLOADING, 2);
        when(uploadRepository.findByContentMd5ForUpdate("md5")).thenReturn(Optional.of(upload));

        assertThatThrownBy(() -> service.complete("md5", List.of(
                new MultipartUploadService.PartETag(0, "etag-a"),
                new MultipartUploadService.PartETag(0, "etag-b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无效");

        verifyNoInteractions(s3);
        verify(uploadRepository, never()).delete(any(StoredFileUpload.class));
        verify(storedFileRepository, never()).delete(any());
    }

    @Test
    void idempotencyKeyIsNamespacedByOwner() {
        String appOne = MultipartUploadService.scopedContentMd5("open-app", 1L, "same-md5");
        String appTwo = MultipartUploadService.scopedContentMd5("open-app", 2L, "same-md5");

        assertThat(appOne).hasSize(32).isNotEqualTo(appTwo);
        assertThat(appOne).isEqualTo(MultipartUploadService.scopedContentMd5("open-app", 1L, "same-md5"));
    }

    private static StoredFileUpload upload(String status, int totalChunks) {
        StoredFileUpload upload = new StoredFileUpload();
        upload.setContentMd5("md5");
        upload.setStoredFileId(42L);
        upload.setStorageKey("files/f.bin");
        upload.setStatus(status);
        upload.setTotalChunks(totalChunks);
        return upload;
    }
}
