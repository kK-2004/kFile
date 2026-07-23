package com.kk.security.oauth;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * OAuth refresh token：可轮换，绑定 grant（token family）。
 *
 * <p>数据库仅保存不可逆哈希（{@code tokenHash}）。每次 refresh 轮换：旧 token 标记 consumed，
 * 签发新 token。已 consumed 的旧 token 再次出现 → 整族（grant）吊销。
 */
@Getter
@Setter
@Entity
@Table(
        name = "oauth_refresh_token",
        uniqueConstraints = @UniqueConstraint(columnNames = "token_hash"),
        indexes = {
                @Index(name = "idx_ort_grant", columnList = "grant_id"),
                @Index(name = "idx_ort_expires", columnList = "expires_at")})
public class OAuthRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** token 明文的 SHA-256 哈希（十六进制）。 */
    @Column(name = "token_hash", nullable = false, length = 128, unique = true)
    private String tokenHash;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "grant_id", nullable = false)
    private OAuthAuthorizationGrant grant;

    /** client_id 字符串。 */
    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @CreationTimestamp
    private Instant createdAt;

    /** 过期时刻（默认 30 天）。 */
    private Instant expiresAt;

    /** 是否已被轮换消费（consumed）。 */
    @Column(nullable = false)
    private boolean consumed = false;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** 是否已吊销。 */
    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
