package com.kk.storage.repo;

import com.kk.storage.entity.StoredFileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StoredFileUploadRepository extends JpaRepository<StoredFileUpload, Long> {

    Optional<StoredFileUpload> findByContentMd5(String contentMd5);

    /** 串行化同一上传的完成请求，避免并发 CompleteMultipartUpload 互相破坏。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from StoredFileUpload u where u.contentMd5 = :contentMd5")
    Optional<StoredFileUpload> findByContentMd5ForUpdate(@Param("contentMd5") String contentMd5);

    Optional<StoredFileUpload> findByStoredFileId(Long storedFileId);

    /** 定时清理：扫描超时仍未完成的上传 */
    List<StoredFileUpload> findByStatusAndUpdatedAtBefore(String status, Instant before);
}
