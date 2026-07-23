package com.kk.mcp;

import com.kk.security.oauth.McpBearerAuthFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 后端 MCP porter：在 Spring AI MCP server 与本地 {@code ToolCallbackProvider} 之间完成进程内调用的
 * 薄适配层（任务 7.2 / 7.3）。
 *
 * <p>porter 的安全不变量（实现 + 运行时断言）：
 * <ul>
 *   <li><b>无出站 MCP client</b>：porter 不引用、不实例化任何 MCP client，不发起网络请求，
 *       不连接本机或远端 MCP server，不维护工具清单副本。工具结果直接来自本地
 *       {@link McpProjectTools} 业务实现。</li>
 *   <li><b>身份只来自请求上下文</b>：身份由 {@link McpBearerAuthFilter} 在 HTTP bearer 边界建立，
 *       porter 通过 {@link #currentSubject()} 从不可伪造的 SecurityContext 取得 subject/client/resource，
 *       不接受工具参数中的身份字段（见 {@link McpToolRegistration}）。</li>
 *   <li><b>MCP session 绑定首次认证身份</b>：{@link #assertSameSubject(String)} 用于在 transport 复用
 *       session 时拒绝跨用户/client 的身份复用。</li>
 * </ul>
 *
 * <p>实际工具调用由 Spring AI MCP server 经 {@code ToolCallbackProvider} 直接转发到
 * {@link McpToolRegistration} 装饰后的回调；porter 不重复实现 MCP 协议路由，仅提供身份契约。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpPorter {

    /**
     * 取得当前请求的 OAuth subject（AdminUser username）。仅从当前 SecurityContext 取得，
     * 该上下文由 {@link McpBearerAuthFilter} 建立，不可由工具参数伪造。
     *
     * @return subject username；未认证返回 null。
     */
    public String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }

    /**
     * 断言当前请求身份与期望 subject 一致，拒绝跨用户/client 的 MCP session 复用（任务 7.3）。
     *
     * @param expectedSubject session 首次认证绑定的 subject。
     * @throws IllegalStateException 不一致时抛出，调用方应拒绝该请求。
     */
    public void assertSameSubject(String expectedSubject) {
        String current = currentSubject();
        if (current == null) {
            log.warn("BIZ action=MCP_PORTER_SUBJECT_NULL 拒绝无身份的 session 复用");
            throw new IllegalStateException("MCP session 复用要求已认证身份");
        }
        if (expectedSubject != null && !expectedSubject.equals(current)) {
            log.warn(
                    "BIZ action=MCP_PORTER_SUBJECT_MISMATCH expected={} actual={} 拒绝跨用户 session 复用",
                    expectedSubject,
                    current);
            throw new IllegalStateException("MCP session 身份不一致，拒绝跨用户复用");
        }
    }
}
