package com.kk.security.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.security.entity.AdminUser;
import com.kk.security.gateway.GatewayTokenVerifier.GatewayIdentity;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** 网关 Bearer 过滤器：通过即以 kfile 账号建立上下文；token 无效 401、账号不可用 403，均不回落会话。 */
class GatewayBearerAuthFilterTest {

    private final GatewayTokenVerifier verifier = mock(GatewayTokenVerifier.class);
    private final GatewayUserResolver resolver = mock(GatewayUserResolver.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final GatewayAuthProperties props = new GatewayAuthProperties();
    private GatewayBearerAuthFilter filter;
    private final GatewayIdentity identity = new GatewayIdentity("7351234567890123456", "alice", "s1");

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        filter = new GatewayBearerAuthFilter(props, verifier, resolver);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest bearer() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/projects");
        request.addHeader("Authorization", "Bearer t");
        return request;
    }

    @Test
    void boundAccountAuthenticatedWithRole() throws Exception {
        AdminUser user = new AdminUser();
        user.setUsername("alice");
        user.setRole("SUPER");
        when(verifier.verify("t")).thenReturn(Optional.of(identity));
        when(resolver.resolve(identity)).thenReturn(new GatewayUserResolver.Bound(user));

        filter.doFilter(bearer(), new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getName()).isEqualTo("alice");
            assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SUPER");
        });
    }

    @Test
    void invalidTokenIs401WithoutFallback() throws Exception {
        when(verifier.verify("t")).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(bearer(), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("GATEWAY_TOKEN_INVALID");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void notProvisionedIs403() throws Exception {
        when(verifier.verify("t")).thenReturn(Optional.of(identity));
        when(resolver.resolve(identity)).thenReturn(new GatewayUserResolver.NotProvisioned());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(bearer(), response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCOUNT_NOT_PROVISIONED").contains("alice");
    }

    @Test
    void noBearerOrDisabledPassesThrough() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/hero"), new MockHttpServletResponse(), chain);
        props.setEnabled(false);
        filter.doFilter(bearer(), new MockHttpServletResponse(), chain);
        verify(chain, org.mockito.Mockito.times(2)).doFilter(any(), any());
        verify(verifier, never()).verify(any());
    }
}
