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
            writeTokenInvalid(response);
            return;
        }

        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        McpTokenService.AuthResult result = tokenService.authenticate(rawToken);
        if (result == null) {
            writeTokenInvalid(response);
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                result.user().getUsername(), null, result.authorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    /**
     * 统一的 token 失败响应：401 + JSON {errorCode:"TOKEN_INVALID"}。
     * bridge 据此识别"本地 token 已失效（吊销/过期/无效）"，清本地 token 重新登录；
     * 与网络抖动等可重试错误区分开。
     */
    private void writeTokenInvalid(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"errorCode\":\"TOKEN_INVALID\"}");
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
