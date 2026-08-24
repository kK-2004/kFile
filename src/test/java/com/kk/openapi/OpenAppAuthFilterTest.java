package com.kk.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.repo.OpenAppRepository;
import com.kk.security.oauth.OAuthCrypto;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 开放 API appToken 过滤器测试：有效 token 建立应用身份；缺失/未注册（含已轮换）/已禁用不设认证；
 * 仅作用于 /api/open 路径；lastUsedAt 节流更新。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenAppAuthFilterTest {

    @Mock private OpenAppRepository openAppRepository;
    @Mock private FilterChain chain;
    @Spy private final OAuthCrypto crypto = new OAuthCrypto();

    @InjectMocks
    private OpenAppAuthFilter filter;

    private final String rawToken = "kapp_test-token";
    private final String tokenHash = crypto.sha256Hex(rawToken);

    private OpenApp enabledApp() {
        OpenApp app = new OpenApp();
        app.setId(7L);
        app.setAppName("crm");
        app.setTokenHash(tokenHash);
        app.setEnabled(true);
        return app;
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String uri, String header) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        if (header != null) req.addHeader("Authorization", header);
        return req;
    }

    @Test
    void validTokenEstablishesAppIdentity() throws Exception {
        OpenApp app = enabledApp();
        when(openAppRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(app));

        filter.doFilter(request("/api/open/uploads", "Bearer " + rawToken),
                new MockHttpServletResponse(), chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).anySatisfy(a -> assertThat(a.getAuthority()).isEqualTo("ROLE_OPEN_APP"));
        assertThat(auth.getPrincipal()).isInstanceOf(OpenAppPrincipal.class);
        assertThat(((OpenAppPrincipal) auth.getPrincipal()).appName()).isEqualTo("crm");
        // lastUsedAt 为空时立即更新
        verify(openAppRepository).save(any(OpenApp.class));
    }

    @Test
    void missingOrMalformedHeaderLeavesUnauthenticated() throws Exception {
        filter.doFilter(request("/api/open/uploads", null), new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter.doFilter(request("/api/open/uploads", "Basic xyz"), new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(openAppRepository, never()).findByTokenHash(any());
    }

    @Test
    void unknownOrRotatedTokenLeavesUnauthenticated() throws Exception {
        // 未注册 / 已轮换：哈希查不到
        when(openAppRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        filter.doFilter(request("/api/open/uploads", "Bearer kapp_gone"), new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void disabledAppLeavesUnauthenticated() throws Exception {
        OpenApp app = enabledApp();
        app.setEnabled(false);
        when(openAppRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(app));

        filter.doFilter(request("/api/open/uploads", "Bearer " + rawToken),
                new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonOpenApiPathIsIgnored() throws Exception {
        // 仅 appToken、无 session 的请求访问 /api/admin/**：本过滤器不介入，由 web 链返回 401
        filter.doFilter(request("/api/admin/users", "Bearer " + rawToken),
                new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(openAppRepository, never()).findByTokenHash(any());
    }

    @Test
    void lastUsedAtUpdateIsThrottled() throws Exception {
        OpenApp app = enabledApp();
        app.setLastUsedAt(Instant.now()); // 刚更新过
        when(openAppRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(app));

        filter.doFilter(request("/api/open/uploads", "Bearer " + rawToken),
                new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(openAppRepository, never()).save(any());
    }
}
