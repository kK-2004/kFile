package com.kk.storage.repo;

import com.kk.storage.entity.MultipartInitLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MultipartInitLockRepository extends JpaRepository<MultipartInitLock, String> {

    @Modifying
    @Query(value = "insert ignore into multipart_init_lock(content_key) values (:contentKey)", nativeQuery = true)
    int insertIgnore(@Param("contentKey") String contentKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from MultipartInitLock l where l.contentKey = :contentKey")
    Optional<MultipartInitLock> findForUpdate(@Param("contentKey") String contentKey);
}
