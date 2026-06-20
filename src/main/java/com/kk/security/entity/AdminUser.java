package com.kk.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "admin_users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class AdminUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 100)
    private String password; // BCrypt

    // SUPER or ADMIN
    @Column(nullable = false, length = 16)
    private String role;

    /** 文件管理空间配额（字节）；null=未设/继承全局，0=不限（SUPER），>0=独立配额 */
    @Column(name = "quota_bytes")
    private Long quotaBytes;

    private Boolean enabled = true;

    @CreationTimestamp
    private Instant createdAt;
}
