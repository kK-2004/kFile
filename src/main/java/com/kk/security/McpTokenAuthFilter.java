package com.kk.security;

import com.kk.security.service.McpTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MCP 长期令牌鉴权过滤器：与 session 认证并存。
 * 仅处理携带 Authorization: Bearer <token> 的请求：
 *  - 校验通过 → 以绑定 AdminUser 身份（principal=username, authorities=角色）建立认证上下文，
 *    使后续 canManageProject / myProjects 等零改动复用既有权限逻辑。
 *  - 校验失败（无 token / 无效 / 过期 / 吊销）→ 不设置认证，交由后续 RestAuthenticationEntryPoint 返回 401。
 * principal 设为 username，与 session 登录一致，便于 controller 用 auth.getName() 取用户。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpTokenAuthFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";
    private final McpTokenService tokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isMcpTransportRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未认证");
            return;
        }

        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        McpTokenService.AuthResult result = tokenService.authenticate(rawToken);
        if (result == null) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未认证");
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                result.user().getUsername(), null, result.authorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    private boolean isMcpTransportRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith("/mcp/");
    }
}
