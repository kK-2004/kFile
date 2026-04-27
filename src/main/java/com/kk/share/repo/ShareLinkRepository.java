package com.kk.share.repo;

import com.kk.share.entity.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {
    Optional<ShareLink> findByCode(String code);

    @Modifying
    @Query("DELETE FROM ShareLink s WHERE s.expireAt IS NOT NULL AND s.expireAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}
