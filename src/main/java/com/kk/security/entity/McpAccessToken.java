package com.kk.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * MCP 长期访问令牌：与现有 session 认证并存。
 * 仅存储 token 的 SHA-256 哈希（tokenHash），明文只在签发时返回一次。
 * 有效期 6 个月（expiresAt = createdAt + 6 月），可吊销（revoked）。
 */
@Getter
@Setter
@Entity
@Table(
        name = "mcp_access_tokens",
        uniqueConstraints = @UniqueConstraint(columnNames = "token_hash")
)
public class McpAccessToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // token 明文的 SHA-256 哈希（十六进制）
    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private AdminUser user;

    @CreationTimestamp
    private Instant createdAt;

    // 过期时刻 = createdAt + 6 月
    private Instant expiresAt;

    private Boolean revoked = false;

    private Instant lastUsedAt;
}
