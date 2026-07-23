package com.kk.security.oauth;

import com.kk.security.entity.AdminUser;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Authorization code：短时、单次使用，绑定 client/redirect URI/resource/PKCE challenge/用户。
 *
 * <p>数据库仅保存 code 的不可逆哈希（{@code codeHash}）与 PKCE challenge（challenge 本身非凭据）。
 * 明文 code 只在授权响应构造期间存在，不进入日志。
 */
@Getter
@Setter
@Entity
@Table(
        name = "oauth_authorization_code",
        uniqueConstraints = @UniqueConstraint(columnNames = "code_hash"),
        indexes = {@Index(name = "idx_oac_expires", columnList = "expires_at")})
public class OAuthAuthorizationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** code 明文的 SHA-256 哈希（十六进制）。 */
    @Column(name = "code_hash", nullable = false, length = 128, unique = true)
    private String codeHash;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AdminUser user;

    /** client_id 字符串。 */
    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    /** 绑定的 redirect URI（精确值，兑换时必须一致）。 */
    @Column(name = "redirect_uri", nullable = false, length = 1024)
    private String redirectUri;

    /** 绑定的 resource URL（audience）。 */
    @Column(name = "resource_uri", nullable = false, length = 512)
    private String resourceUri;

    /** scope。 */
    @Column(nullable = false, length = 128)
    private String scope;

    /** PKCE code challenge（S256）。 */
    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @CreationTimestamp
    private Instant createdAt;

    /** 过期时刻（默认 5 分钟）。 */
    private Instant expiresAt;

    /** 是否已消费（单次消费）。 */
    @Column(nullable = false)
    private boolean consumed = false;

    /** 消费时刻。 */
    @Column(name = "consumed_at")
    private Instant consumedAt;
}
