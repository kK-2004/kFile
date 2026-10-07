package com.kk.security.oauth;

import jakarta.persistence.*;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * OAuth client registration（预注册或 RFC 7591 动态注册）。
 *
 * <p>保存 client id、精确 redirect URIs、grant/response 类型、认证方式、创建/最后使用/过期状态。
 * 仅支持 Authorization Code + PKCE 所需的 public-client 元数据
 * （{@code token_endpoint_auth_method=none}）。
 */
@Getter
@Setter
@Entity
@Table(
        name = "oauth_client_registration",
        uniqueConstraints = @UniqueConstraint(columnNames = "client_id"),
        indexes = @Index(name = "idx_ocr_created", columnList = "created_at"))
public class OAuthClientRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 公开的 client identifier。 */
    @Column(name = "client_id", nullable = false, length = 128, unique = true)
    private String clientId;

    /** 展示名称（动态注册取 client_name，预注册取约定名）。 */
    @Column(name = "client_name", length = 256)
    private String clientName;

    /**
     * 精确 redirect URIs（JSON 数组字符串存储）。授权请求的 redirect_uri 必须与其中之一完全一致，
     * 不做前缀匹配。localhost 可使用 HTTP，其余必须 HTTPS。
     */
    @Column(name = "redirect_uris", nullable = false, length = 2048)
    private String redirectUrisJson;

    /** 允许的 grant 类型，固定含 authorization_code。 */
    @Column(name = "grant_types", nullable = false, length = 256)
    private String grantTypes = "[\"authorization_code\"]";

    /** 允许的 response 类型，固定含 code。 */
    @Column(name = "response_types", nullable = false, length = 128)
    private String responseTypes = "[\"code\"]";

    /** token endpoint 认证方式：public client 固定 none。 */
    @Column(name = "token_endpoint_auth_method", nullable = false, length = 32)
    private String tokenEndpointAuthMethod = "none";

    /** 允许的 scope，默认 mcp:tools。 */
    @Column(name = "scope", nullable = false, length = 128)
    private String scope = "mcp:tools";

    /** 是否动态注册（true）或预注册（false）。 */
    @Column(name = "is_dynamic", nullable = false)
    private boolean dynamic = false;

    /** 注册来源 IP（动态注册审计）。 */
    @Column(name = "registered_from", length = 64)
    private String registeredFrom;

    /** 动态注册的过期时刻（未使用 client 超时清理用）；预注册不过期。 */
    @Column(name = "client_secret_expires_at")
    private Instant clientSecretExpiresAt;

    /** 创建时间。 */
    @CreationTimestamp
    private Instant createdAt;

    /** 最近一次使用（授权/token/调用）时间。 */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** 是否已被禁用/删除。 */
    @Column(name = "disabled", nullable = false)
    private boolean disabled = false;

    /** 解析 redirect URIs 为集合。 */
    public Set<String> redirectUriSet() {
        if (redirectUrisJson == null || redirectUrisJson.isBlank()) {
            return new HashSet<>();
        }
        // 简单 JSON 数组解析，避免引入额外依赖；格式由注册逻辑保证。
        String trimmed = redirectUrisJson.replaceAll("^\\[|\\]$", "").trim();
        if (trimmed.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> set = new HashSet<>();
        for (String part : trimmed.split(",")) {
            String v = part.trim().replaceAll("^\"|\"$", "");
            if (!v.isEmpty()) {
                set.add(v);
            }
        }
        return set;
    }

    /** 请求的 redirect URI 是否与任一已注册值匹配，规则见 {@link #redirectUriMatches}。 */
    public boolean matchesRedirectUri(String requested) {
        if (requested == null) {
            return false;
        }
        return redirectUriSet().stream().anyMatch(r -> redirectUriMatches(r, requested));
    }

    /**
     * redirect URI 匹配：默认精确匹配；loopback（http + localhost/127.0.0.1/[::1]）按 RFC 8252 §7.3
     * 忽略端口，scheme、host、path、query 仍须一致。本地 agent 每次用随机端口回调，端口不参与匹配。
     */
    static boolean redirectUriMatches(String registered, String requested) {
        if (registered.equals(requested)) {
            return true;
        }
        try {
            URI a = URI.create(registered);
            URI b = URI.create(requested);
            return isLoopbackHttp(a)
                    && isLoopbackHttp(b)
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && java.util.Objects.equals(a.getRawPath(), b.getRawPath())
                    && java.util.Objects.equals(a.getRawQuery(), b.getRawQuery())
                    && a.getRawFragment() == null
                    && b.getRawFragment() == null
                    && a.getRawUserInfo() == null
                    && b.getRawUserInfo() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isLoopbackHttp(URI uri) {
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
                && host != null
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
    }
}
