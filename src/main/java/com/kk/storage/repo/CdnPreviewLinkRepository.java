package com.kk.storage.repo;

import com.kk.storage.entity.CdnPreviewLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CdnPreviewLinkRepository extends JpaRepository<CdnPreviewLink, Long> {

    Optional<CdnPreviewLink> findByToken(String token);

    boolean existsByToken(String token);

    List<CdnPreviewLink> findAllByOrderByCreatedAtDesc();

    List<CdnPreviewLink> findByCreatedByOrderByCreatedAtDesc(Long createdBy);

    List<CdnPreviewLink> findByExpireAtBefore(Instant cutoff);

    /** 底层 stored_file 已被删除的 CDN 分享。 */
    @Query(value = """
            select c.* from cdn_preview_link c
            where not exists (select 1 from stored_file f where f.id = c.stored_file_id)
            """, nativeQuery = true)
    List<CdnPreviewLink> findWithoutExistingFile();
}
