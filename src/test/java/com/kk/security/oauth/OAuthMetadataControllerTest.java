package com.kk.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.kk.config.McpOAuthProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * 任务 3.4：metadata 与 challenge 测试（纯单元测试，无 Spring 上下文）。
 *
 * <p>核心安全属性：恶意 Host/转发头不能改变 issuer/resource/endpoint URL——所有 URL 必须来自配置的
 * {@code app.public-base-url}，而 controller / entry point 不读取请求头中的 Host/Forwarded。
 */
class OAuthMetadataControllerTest {

    private static final String CONFIGURED_BASE = "https://file.example.com";

    private McpOAuthProperties props() {
        McpOAuthProperties p = new McpOAuthProperties();
        p.setPublicBaseUrl(CONFIGURED_BASE);
        p.setMcpEndpoint("/mcp");
        p.setScope("mcp:tools");
        return p;
    }

    @Test
    void protectedResourceMetadata_returnsCanonicalResourceAndAuthorizationServers() {
        OAuthMetadataController controller = new OAuthMetadataController(props());
        Map<String, Object> body = controller.protectedResourceMetadata();
        assertThat(body.get("resource")).isEqualTo(CONFIGURED_BASE + "/mcp");
        assertThat(body.get("authorization_servers")).isEqualTo(List.of(CONFIGURED_BASE));
        assertThat(body.get("scopes_supported")).isEqualTo(List.of("mcp:tools"));
    }

    @Test
    void authorizationServerMetadata_publishesEndpointsAndS256() {
        OAuthMetadataController controller = new OAuthMetadataController(props());
        Map<String, Object> m = controller.authorizationServerMetadata();
        assertThat(m.get("issuer")).isEqualTo(CONFIGURED_BASE);
        assertThat(m.get("authorization_endpoint")).isEqualTo(CONFIGURED_BASE + "/oauth2/authorize");
        assertThat(m.get("token_endpoint")).isEqualTo(CONFIGURED_BASE + "/oauth2/token");
        assertThat(m.get("registration_endpoint")).isEqualTo(CONFIGURED_BASE + "/oauth2/register");
        assertThat(m.get("revocation_endpoint")).isEqualTo(CONFIGURED_BASE + "/oauth2/revoke");
        assertThat(m.get("code_challenge_methods_supported")).isEqualTo(List.of("S256"));
        @SuppressWarnings("unchecked")
        List<String> grantTypes = (List<String>) m.get("grant_types_supported");
        assertThat(grantTypes).contains("authorization_code", "refresh_token");
    }

    @Test
    void authorizationServerMetadata_hasNoDependencyOnRequestHeaders() {
        // controller 不接收 HttpServletRequest，因此不可能反射恶意 Host/转发头。
        OAuthMetadataController controller = new OAuthMetadataController(props());
        Map<String, Object> m = controller.authorizationServerMetadata();
        for (Object v : m.values()) {
            if (v instanceof String s) {
                assertThat(s).doesNotContain("evil");
            }
        }
    }

    @Test
    void bearerEntryPoint_setsResourceMetadataAndScopeChallenge() {
        McpBearerAuthenticationEntryPoint entryPoint =
                new McpBearerAuthenticationEntryPoint(props());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockHttpServletRequest req = new MockHttpServletRequest();
        // 模拟恶意 Host/转发头
        req.addHeader("Host", "evil.attacker.com");
        req.addHeader("X-Forwarded-Host", "evil.attacker.com");
        req.addHeader("X-Forwarded-Proto", "http");
        try {
            entryPoint.commence(req, resp, new AuthenticationCredentialsNotFoundException("test"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(resp.getStatus()).isEqualTo(401);
        String www = resp.getHeader("WWW-Authenticate");
        assertThat(www).contains("resource_metadata=\"" + CONFIGURED_BASE);
        assertThat(www).contains("scope=\"mcp:tools\"");
        // 关键：恶意 Host 不得出现在 challenge
        assertThat(www).doesNotContain("evil.attacker.com");
    }

    @Test
    void bearerEntryPoint_returns401StatusAndJsonBody() throws Exception {
        McpBearerAuthenticationEntryPoint entryPoint =
                new McpBearerAuthenticationEntryPoint(props());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        entryPoint.commence(
                new MockHttpServletRequest(),
                resp,
                new AuthenticationCredentialsNotFoundException("test"));
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("invalid_token");
    }
}
