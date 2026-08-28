package com.kk.storage.repo;

import com.kk.storage.entity.CdnPreviewLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface CdnPreviewLinkRepository extends JpaRepository<CdnPreviewLink, Long> {

    Optional<CdnPreviewLink> findByToken(String token);

    boolean existsByToken(String token);

    List<CdnPreviewLink> findAllByOrderByCreatedAtDesc();

    List<CdnPreviewLink> findByCreatedByOrderByCreatedAtDesc(Long createdBy);
}
