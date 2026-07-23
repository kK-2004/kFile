package com.kk.security.oauth;

import com.kk.security.entity.AdminUser;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * OAuth authorization grant：一次成功授权（consent）建立的授权链。
 *
 * <p>关联 AdminUser、client、scope、resource、授权/吊销状态。同一 client 可对同一用户存在多条
 * grant（例如重新授权）。access token 与 refresh token 通过 token family 关联到此 grant；
 * refresh token 重用时整族吊销即把本 grant 置为 revoked。
 */
@Getter
@Setter
@Entity
@Table(
        name = "oauth_authorization_grant",
        indexes = {
                @Index(name = "idx_oag_user", columnList = "user_id"),
                @Index(name = "idx_oag_client", columnList = "client_id"),
                @Index(name = "idx_oag_user_client", columnList = "user_id,client_id")
        })
public class OAuthAuthorizationGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AdminUser user;

    /** client identifier（冗余存储 client_id 字符串，便于查询与展示）。 */
    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id", nullable = false)
    private OAuthClientRegistration registration;

    /** 授权的 scope，默认 mcp:tools。 */
    @Column(nullable = false, length = 128)
    private String scope = "mcp:tools";

    /** 绑定的规范化 MCP resource URL（audience）。 */
    @Column(name = "resource_uri", nullable = false, length = 512)
    private String resourceUri;

    /** 授权时刻。 */
    @CreationTimestamp
    private Instant createdAt;

    /** 最近一次刷新/使用时刻。 */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** 是否已吊销。 */
    @Column(nullable = false)
    private boolean revoked = false;

    /** 吊销时刻。 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 吊销原因（access_denied / reuse_detected / admin_disabled / user_revoked / ...）。 */
    @Column(name = "revocation_reason", length = 64)
    private String revocationReason;
}
