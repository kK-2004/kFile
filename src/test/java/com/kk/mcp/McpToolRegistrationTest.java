package com.kk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务 7.4 / 7.5：McpToolRegistration 安全装饰器测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>工具参数中的 {@code __kfile_access_token} 被剥离，且绝不用于建立身份。</li>
 *   <li>无 HTTP bearer 上下文（未认证）→ 401。</li>
 *   <li>已认证 → 调用委托回调，身份来自 SecurityContext。</li>
 *   <li>剥离后的干净参数传给 delegate（无禁止字段）。</li>
 * </ul>
 */
class McpToolRegistrationTest {

    private McpToolRegistration registration;
    private ToolCallback delegate;

    @BeforeEach
    void setUp() {
        registration = new McpToolRegistration();
        delegate = mock(ToolCallback.class);
        when(delegate.call(anyString())).thenReturn("ok");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticated_rejectsWith401() {
        // 无 SecurityContext → 401，不调用 delegate
        ToolCallback g = guardedOf();
        assertThatThrownBy(() -> g.call("{}"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        e -> {
                            HttpStatus status =
                                    HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
                            assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
                        });
        verify(delegate, org.mockito.Mockito.never()).call(anyString());
    }

    @Test
    void authenticated_delegatesToCallback() {
        authenticate("alice");
        ToolCallback g = guardedOf();
        String result = g.call("{\"name\":\"test\"}");
        assertThat(result).isEqualTo("ok");
        verify(delegate).call(anyString());
    }

    @Test
    void forbiddenAccessTokenArg_strippedNotUsedForIdentity() {
        // 即使模型注入了 __kfile_access_token，也必须被剥离，且身份只来自 SecurityContext
        authenticate("alice"); // 真实身份
        ToolCallback g = guardedOf();
        g.call("{\"name\":\"test\",\"__kfile_access_token\":\"FAKE_TOKEN\"}");
        // delegate 收到的输入不应包含禁止字段
        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delegate).call(captor.capture());
        assertThat(captor.getValue()).doesNotContain("__kfile_access_token");
        assertThat(captor.getValue()).doesNotContain("FAKE_TOKEN");
        // identity 仍是 alice（未因 fake token 改变）
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("alice");
    }

    @Test
    void forbiddenTokenArgWithoutAuth_still401() {
        // 无身份时，即使注入了 token 参数也不应建立身份
        ToolCallback g = guardedOf();
        assertThatThrownBy(() -> g.call("{\"__kfile_access_token\":\"FAKE_TOKEN\"}"))
                .isInstanceOf(ResponseStatusException.class);
        verify(delegate, org.mockito.Mockito.never()).call(anyString());
    }

    /** 构造一个包装了 delegate 的受保护回调。 */
    private ToolCallback guardedOf() {
        // 通过反射调用私有方法 withIdentityGuard
        try {
            var method = McpToolRegistration.class.getDeclaredMethod("withIdentityGuard", ToolCallback.class);
            method.setAccessible(true);
            return (ToolCallback) method.invoke(registration, delegate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                username, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER"))));
    }
}
