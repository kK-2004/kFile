package com.kk.share.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "share_link")
public class ShareLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    private Long projectId;

    @Column(columnDefinition = "LONGTEXT")
    private String data;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expireAt;
}
