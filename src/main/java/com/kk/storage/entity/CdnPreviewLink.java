package com.kk.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Stable public link which resolves to a short-lived inline media URL. */
@Getter
@Setter
@Entity
@Table(name = "cdn_preview_link", indexes = {
        @Index(name = "idx_cdn_preview_file", columnList = "stored_file_id")
})
public class CdnPreviewLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "stored_file_id", nullable = false)
    private Long storedFileId;

    /** Null means permanent until the stored file is removed. */
    private Instant expireAt;

    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;
}
