package com.kk.security.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

    /** 统一认证网关用户全局 ID（token sub）；首次经网关登录时按用户名绑定，未经网关登录过为 null。 */
    @Column(unique = true)
    @JsonSerialize(using = ToStringSerializer.class) // 雪花 long 超出 JS 安全整数，JSON 一律字符串
    private Long guid;

    @CreationTimestamp
    private Instant createdAt;
}
