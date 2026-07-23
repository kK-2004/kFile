package com.kk.security.oauth;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * OAuth access token（不透明）：短期、绑定 client/用户/scope/resource。
 *
 * <p>数据库仅保存不可逆哈希（{@code tokenHash}），明文只在 token endpoint 响应构造期间存在。
 * 过期/吊销/resource 不匹配时 MCP resource server 拒绝。
 */
@Getter
@Setter
@Entity
@Table(
        name = "oauth_access_token",
        uniqueConstraints = @UniqueConstraint(columnNames = "token_hash"),
        indexes = {
                @Index(name = "idx_oat_grant", columnList = "grant_id"),
                @Index(name = "idx_oat_expires", columnList = "expires_at")})
public class OAuthAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** token 明文的 SHA-256 哈希（十六进制）。 */
    @Column(name = "token_hash", nullable = false, length = 128, unique = true)
    private String tokenHash;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "grant_id", nullable = false)
    private OAuthAuthorizationGrant grant;

    /** 冗余：subject（AdminUser id 字符串），便于 token 内省/校验快速取用户。 */
    @Column(name = "subject", nullable = false, length = 64)
    private String subject;

    /** client_id 字符串。 */
    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    /** scope。 */
    @Column(nullable = false, length = 128)
    private String scope;

    /** 绑定的 resource URL（audience）。 */
    @Column(name = "resource_uri", nullable = false, length = 512)
    private String resourceUri;

    @CreationTimestamp
    private Instant createdAt;

    /** 过期时刻（默认 15 分钟）。 */
    private Instant expiresAt;

    /** 是否已吊销。 */
    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
