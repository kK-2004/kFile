package com.kk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 任务 7.2 / 7.3 / 7.5：McpPorter 身份契约测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>无身份 → currentSubject 为 null，assertSameSubject 拒绝</li>
 *   <li>身份一致 → 通过</li>
 *   <li>身份不一致（跨用户 session 复用）→ 拒绝</li>
 *   <li>并发用户隔离：两个线程各自只看到自己的身份</li>
 * </ul>
 */
class McpPorterTest {

    private final McpPorter porter = new McpPorter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentSubject_noAuth_returnsNull() {
        assertThat(porter.currentSubject()).isNull();
    }

    @Test
    void currentSubject_anonymous_returnsNull() {
        authenticate("anonymousUser");
        assertThat(porter.currentSubject()).isNull();
    }

    @Test
    void currentSubject_authenticated_returnsUsername() {
        authenticate("alice");
        assertThat(porter.currentSubject()).isEqualTo("alice");
    }

    @Test
    void assertSameSubject_matching_passes() {
        authenticate("alice");
        porter.assertSameSubject("alice");
    }

    @Test
    void assertSameSubject_mismatch_rejects() {
        authenticate("alice");
        assertThatThrownBy(() -> porter.assertSameSubject("bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void assertSameSubject_noAuth_rejects() {
        assertThatThrownBy(() -> porter.assertSameSubject("alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentUsers_isolatedIdentity() throws Exception {
        // 模拟 transport 在不同执行线程间调度两个用户的请求
        int n = 2;
        Thread[] threads = new Thread[n];
        AssertionError[] errs = new AssertionError[n];
        String[] subjects = {"alice", "bob"};
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String who = subjects[i];
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    authenticate(who);
                                    // 每个线程只看到自己的身份
                                    assertThat(porter.currentSubject()).isEqualTo(who);
                                    porter.assertSameSubject(who);
                                    // 不应看到另一个线程的身份
                                    String other = subjects[1 - idx];
                                    assertThat(porter.currentSubject()).isNotEqualTo(other);
                                } catch (AssertionError e) {
                                    errs[idx] = e;
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        for (AssertionError e : errs) {
            if (e != null) {
                throw e;
            }
        }
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                username, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER"))));
    }
}
