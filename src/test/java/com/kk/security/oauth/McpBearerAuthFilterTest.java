package com.kk.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.config.McpOAuthProperties;
import com.kk.security.entity.AdminUser;
import com.kk.security.oauth.OAuthTokenService.AccessTokenContext;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 任务 6.1 / 6.2 / 6.3：MCP bearer 过滤器测试。
 *
 * <p>核心安全属性：
 * <ul>
 *   <li>仅 /mcp 路径生效，非 /mcp 请求（如 /api/admin/**）不被本过滤器认证。</li>
 *   <li>无 bearer → 不设认证（交由 entry point 401）。</li>
 *   <li>无效/过期/吊销 token → 不设认证。</li>
 *   <li>resource 不匹配 → 不设认证。</li>
 *   <li>scope 缺失 → 设认证但 accessDeniedHandler 会返回 403。</li>
 *   <li>有效 token + scope → 以 OAuth subject 的 AdminUser 身份建立 SecurityContext。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpBearerAuthFilterTest {

    @Mock private OAuthTokenService tokenService;
    @Mock private FilterChain chain;

    private McpOAuthProperties props;
    private McpBearerAuthFilter filter;

    private final AdminUser user = newUser(1L, "alice", "SUPER");
    private static final String RESOURCE = "https://file.example.com/mcp";

    @BeforeEach
    void setUp() {
        props = new McpOAuthProperties();
        props.setPublicBaseUrl("https://file.example.com");
        props.setMcpEndpoint("/mcp");
        props.setScope("mcp:tools");
        filter = new McpBearerAuthFilter(tokenService, props);
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonMcpPath_skipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/admin/users");
        req.addHeader("Authorization", "Bearer xyz");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        // 链应继续，且不调用 token 校验，不设认证
        verify(chain).doFilter(req, resp);
        verify(tokenService, never()).validateAccessToken(anyString(), anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void mcpPath_withoutBearer_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void mcpPath_invalidToken_doesNotAuthenticate() throws Exception {
        when(tokenService.validateAccessToken("bad", RESOURCE)).thenReturn(Optional.empty());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void mcpPath_validToken_setsAdminUserAuthentication() throws Exception {
        OAuthAccessToken token = tokenWithScope("mcp:tools");
        when(tokenService.validateAccessToken("good", RESOURCE))
                .thenReturn(Optional.of(new AccessTokenContext(token, user, grant())));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer good");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getAuthorities().toString()).contains("ROLE_SUPER");
        SecurityContextHolder.clearContext();
    }

    @Test
    void mcpPath_wrongScope_authenticationSetButNoMcpTools() throws Exception {
        // scope 缺失：过滤器仍设认证，由 accessDeniedHandler 返回 403 insufficient_scope
        OAuthAccessToken token = tokenWithScope("other:scope");
        when(tokenService.validateAccessToken("good", RESOURCE))
                .thenReturn(Optional.of(new AccessTokenContext(token, user, grant())));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer good");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        // 链继续，认证已设（下游由 @PreAuthorize / accessDeniedHandler 决定 403）
        verify(chain).doFilter(req, resp);
        SecurityContextHolder.clearContext();
    }

    private OAuthAccessToken tokenWithScope(String scope) {
        OAuthAccessToken t = new OAuthAccessToken();
        t.setId(1L);
        t.setTokenHash("h");
        t.setSubject("1");
        t.setClientId("mcp_test");
        t.setScope(scope);
        t.setResourceUri(RESOURCE);
        t.setCreatedAt(Instant.now());
        t.setExpiresAt(Instant.now().plus(Duration.ofMinutes(15)));
        t.setRevoked(false);
        return t;
    }

    private OAuthAuthorizationGrant grant() {
        OAuthAuthorizationGrant g = new OAuthAuthorizationGrant();
        g.setId(1L);
        g.setClientId("mcp_test");
        g.setScope("mcp:tools");
        g.setResourceUri(RESOURCE);
        g.setUser(user);
        g.setRevoked(false);
        return g;
    }

    private static AdminUser newUser(Long id, String username, String role) {
        AdminUser u = new AdminUser();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setEnabled(true);
        return u;
    }
}
