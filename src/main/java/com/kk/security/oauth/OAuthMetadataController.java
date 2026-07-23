package com.kk.security.oauth;

import com.kk.config.McpOAuthProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 与 MCP 元数据发现（任务 3.1 / 3.2）。
 *
 * <ul>
 *   <li>{@code GET /.well-known/oauth-protected-resource/mcp} — RFC 9728 protected-resource metadata，
 *       返回规范化 {@code resource}、{@code authorization_servers} 与 {@code mcp:tools} scope。</li>
 *   <li>{@code GET /.well-known/oauth-authorization-server} — OAuth 2.0 Authorization Server Metadata，
 *       发布 authorization/token/registration/revocation endpoints 及 {@code S256} PKCE 支持。</li>
 * </ul>
 *
 * <p>所有绝对 URL 均由 {@link McpOAuthProperties#getPublicBaseUrl()} 派生，不信任任意 Host/转发头。
 */
@RestController
@RequiredArgsConstructor
public class OAuthMetadataController {

    private final McpOAuthProperties props;

    /** RFC 9728 Protected Resource Metadata（针对 /mcp）。 */
    @GetMapping("/.well-known/oauth-protected-resource/mcp")
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", props.resourceUrl(),
                "authorization_servers", List.of(props.issuer()),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", List.of(props.getScope()),
                "resource_documentation", props.issuer() + "/docs/mcp");
    }

    /** OAuth 2.0 Authorization Server Metadata。 */
    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> authorizationServerMetadata() {
        String issuer = props.issuer();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", issuer);
        m.put("authorization_endpoint", issuer + "/oauth2/authorize");
        m.put("token_endpoint", issuer + "/oauth2/token");
        m.put("registration_endpoint", issuer + "/oauth2/register");
        m.put("revocation_endpoint", issuer + "/oauth2/revoke");
        m.put("jwks_uri", issuer + "/oauth2/jwks");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        m.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_post"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("scopes_supported", List.of(props.getScope(), "openid"));
        m.put("resource", props.resourceUrl());
        return m;
    }
}
