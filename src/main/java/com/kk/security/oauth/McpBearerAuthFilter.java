package com.kk.security.oauth;

import com.kk.config.McpOAuthProperties;
import com.kk.security.entity.AdminUser;
import com.kk.security.oauth.OAuthTokenService.AccessTokenContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP Bearer 资源服务器过滤器（任务 6.1 / 6.3）。
 *
 * <p>仅作用于规范化 {@code /mcp} Streamable HTTP 端点：
 * <ul>
 *   <li>从 {@code Authorization: Bearer <token>} 取 access token，交由 {@link OAuthTokenService} 校验
 *       （过期/吊销/用户启用/grant 状态/resource audience 完全匹配）。</li>
 *   <li>校验通过 → 以 OAuth subject 对应的 AdminUser 身份（principal=username，
 *       authorities=ROLE_SUPER/ROLE_ADMIN）建立临时 SecurityContext，使既有工具零改动复用权限逻辑。</li>
 *   <li>校验失败（无 token / 无效 / 过期 / 吊销 / resource 不匹配）→ 交由
 *       {@link McpBearerAuthenticationEntryPoint} 返回带 {@code resource_metadata} 的可发现 401。</li>
 * </ul>
 *
 * <p>该链为无 session（{@code STATELESS}），MCP bearer token 只能访问规范化 MCP resource，
 * 不能访问 {@code /api/admin/**}（由各自独立的安全链保证，见 SecurityConfig）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpBearerAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final OAuthTokenService tokenService;
    private final McpOAuthProperties props;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isMcpResourceRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // 无 bearer token → 不设认证，后续 entry point 返回可发现 401
            chain.doFilter(request, response);
            return;
        }

        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        // resource audience 必须与规范化 MCP resource 完全匹配
        AccessTokenContext ctx =
                tokenService.validateAccessToken(rawToken, props.resourceUrl()).orElse(null);
        if (ctx == null) {
            // 校验失败 → 不设认证，entry point 返回 401
            SecurityContextHolder.clearContext();
            chain.doFilter(request, response);
            return;
        }

        // scope 校验：必须含 mcp:tools
        if (!hasMcpToolsScope(ctx.token().getScope())) {
            // scope 缺失 → 由 accessDeniedHandler 返回 403 insufficient_scope
            Authentication auth = buildAuthentication(ctx.user(), request);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = buildAuthentication(ctx.user(), request);
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    private Authentication buildAuthentication(AdminUser user, HttpServletRequest request) {
        List<GrantedAuthority> authorities = roleAuthorities(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return auth;
    }

    private boolean hasMcpToolsScope(String scope) {
        if (scope == null) {
            return false;
        }
        for (String s : scope.split(" ")) {
            if (props.getScope().equals(s.trim())) {
                return true;
            }
        }
        return false;
    }

    private List<GrantedAuthority> roleAuthorities(AdminUser user) {
        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** 仅 /mcp 端点（规范化 MCP resource）。 */
    private boolean isMcpResourceRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        // /mcp 与可能的无尾斜杠变体
        return path.equals("/mcp") || path.startsWith("/mcp");
    }

    /** 设置入口点（供 SecurityConfig 注入到 /mcp 链）。 */
    public AuthenticationEntryPoint entryPoint() {
        return new McpBearerAuthenticationEntryPoint(props);
    }
}
