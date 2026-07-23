package com.kk.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP OAuth / 远程 MCP 相关配置。
 *
 * <p>该配置驱动：
 * <ul>
 *   <li>可信公共基址 {@code app.public-base-url}：用于生成 issuer、protected-resource/authorization-server
 *       metadata 及 MCP resource audience；服务端不信任任意 Host/转发头。</li>
 *   <li>规范化 MCP resource URL（默认 {@code <public-base-url>/mcp}）。</li>
 *   <li>{@code mcp:tools} scope。</li>
 *   <li>authorization code / access token / refresh token 有效期。</li>
 *   <li>Dynamic Client Registration 限流阈值。</li>
 * </ul>
 *
 * <p>校验规则：生产公共基址必须为 HTTPS（localhost 开发例外）；issuer、metadata endpoint 与 MCP
 * resource 必须同配置一致（即都由 {@code publicBaseUrl} 派生，不允许相互矛盾）。
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "app.mcp.oauth")
public class McpOAuthProperties {

    /** 公共基址，如 https://file.ksite.xin。必须以 http(s) 开头，生产环境必须为 HTTPS。 */
    private String publicBaseUrl;

    /** MCP resource 路径（相对基址），默认 /mcp。 */
    private String mcpEndpoint = "/mcp";

    /** OAuth scope，默认 mcp:tools。 */
    private String scope = "mcp:tools";

    /** authorization code 有效期，默认 5 分钟。 */
    private Duration authorizationCodeValidity = Duration.ofMinutes(5);

    /** access token 有效期，默认 15 分钟。 */
    private Duration accessTokenValidity = Duration.ofMinutes(15);

    /** refresh token 有效期，默认 30 天。 */
    private Duration refreshTokenValidity = Duration.ofDays(30);

    /** Dynamic Client Registration：单位时间窗口内单个来源（IP）最大注册次数，默认 10。 */
    private int dcrRateLimit = 10;

    /** DCR 限流时间窗口，默认 1 小时。 */
    private Duration dcrRateWindow = Duration.ofHours(1);

    /** 未使用的动态注册 client 超过该时长后被清理，默认 30 天。 */
    private Duration unusedClientValidity = Duration.ofDays(30);

    /** 是否允许本地开发豁免 HTTPS 校验（基址为 localhost/127.0.0.1 时自动豁免）。 */
    private boolean devHttpsExempt = true;

    /** 规范化的 MCP resource URL（publicBaseUrl + mcpEndpoint），去掉末尾斜杠。 */
    public String resourceUrl() {
        return normalize(publicBaseUrl) + normalizePath(mcpEndpoint);
    }

    /** OAuth issuer URL，与 publicBaseUrl 一致（同一应用同时作为 AS 与 resource server）。 */
    public String issuer() {
        return normalize(publicBaseUrl);
    }

    /** protected-resource metadata URL：{@code <issuer>/.well-known/oauth-protected-resource/mcp}。 */
    public String protectedResourceMetadataUrl() {
        return normalize(publicBaseUrl) + "/.well-known/oauth-protected-resource/mcp";
    }

    /** authorization-server metadata URL：{@code <issuer>/.well-known/oauth-authorization-server}。 */
    public String authorizationServerMetadataUrl() {
        return normalize(publicBaseUrl) + "/.well-known/oauth-authorization-server";
    }

    @PostConstruct
    void validate() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.mcp.oauth.public-base-url (app.public-base-url) 未配置：MCP OAuth issuer/resource/metadata 需要可信公共基址");
        }
        URI uri;
        try {
            uri = URI.create(publicBaseUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("app.public-base-url 不是合法 URL: " + publicBaseUrl, ex);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalStateException("app.public-base-url 缺少 scheme/host: " + publicBaseUrl);
        }
        boolean isLocalhost = isLocalhost(host);
        if (!"https".equalsIgnoreCase(scheme)) {
            if (!("http".equalsIgnoreCase(scheme) && devHttpsExempt && isLocalhost)) {
                throw new IllegalStateException(
                        "app.public-base-url 生产基址必须为 HTTPS（仅 localhost 开发例外）: " + publicBaseUrl);
            }
        }
        // 一致性：resource/metadata/issuer 全部由 publicBaseUrl 派生，不额外接受矛盾配置。
        if (mcpEndpoint == null || !mcpEndpoint.startsWith("/")) {
            throw new IllegalStateException("app.mcp.oauth.mcp-endpoint 必须以 '/' 开头: " + mcpEndpoint);
        }
        log.info("MCP OAuth configured: issuer={}, resource={}, scope={}, "
                        + "codeValidity={}, accessValidity={}, refreshValidity={}",
                issuer(), resourceUrl(), scope,
                authorizationCodeValidity, accessTokenValidity, refreshTokenValidity);
    }

    private static boolean isLocalhost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    private static String normalize(String base) {
        if (base == null) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path;
    }
}
