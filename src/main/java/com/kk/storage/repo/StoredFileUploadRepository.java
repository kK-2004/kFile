package com.kk.storage.repo;

import com.kk.storage.entity.StoredFileUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StoredFileUploadRepository extends JpaRepository<StoredFileUpload, Long> {

    Optional<StoredFileUpload> findByContentMd5(String contentMd5);

    Optional<StoredFileUpload> findByStoredFileId(Long storedFileId);

    /** 定时清理：扫描超时仍未完成的上传 */
    List<StoredFileUpload> findByStatusAndUpdatedAtBefore(String status, Instant before);
}
