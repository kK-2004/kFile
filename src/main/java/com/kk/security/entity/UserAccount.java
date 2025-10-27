package com.kk.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user_accounts", indexes = {
        @Index(name = "uk_user_external", columnList = "externalId", unique = true)
})
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // k-Site 用户的稳定ID（JWT sub）: bigint unsigned
    @Column(nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long externalId;

    @Column(length = 191)
    private String username;

    @Column(length = 191)
    private String email;

    // 本地角色：KF_SUPER / KF_ADMIN / KF_USER（默认）
    @Column(nullable = false, length = 32)
    private String role = "KF_USER";

    @Column(length = 16)
    private String status; // 可选：ACTIVE/LOCKED

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
