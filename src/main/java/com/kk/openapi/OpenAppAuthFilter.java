package com.kk.openapi;

import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.repo.OpenAppRepository;
import com.kk.security.oauth.OAuthCrypto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 开放 API appToken 鉴权过滤器（/api/open/** 链，仿 {@code McpBearerAuthFilter}）。
 * <ul>
 *   <li>从 {@code Authorization: Bearer <appToken>} 取明文，SHA-256 后按 tokenHash 唯一索引查 {@code open_app}。</li>
 *   <li>命中且 enabled=true → 以 {@link OpenAppPrincipal}（ROLE_OPEN_APP）建立临时 SecurityContext，
 *       并节流更新 lastUsedAt（≥60s 一次，失败不影响请求）。</li>
 *   <li>缺失/未注册/已轮换/已禁用 → 不设认证，由 entry point 返回 401 ApiError。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAppAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final long LAST_USED_THROTTLE_SECONDS = 60;

    private final OpenAppRepository openAppRepository;
    private final OAuthCrypto crypto;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isOpenApiRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        OpenApp app = openAppRepository.findByTokenHash(crypto.sha256Hex(rawToken)).orElse(null);
        if (app == null || !app.isEnabled()) {
            SecurityContextHolder.clearContext();
            chain.doFilter(request, response);
            return;
        }

        touchLastUsed(app);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new OpenAppPrincipal(app.getId(), app.getAppName()), null,
                List.of(new SimpleGrantedAuthority("ROLE_OPEN_APP")));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    private void touchLastUsed(OpenApp app) {
        Instant now = Instant.now();
        if (app.getLastUsedAt() != null
                && Duration.between(app.getLastUsedAt(), now).toSeconds() < LAST_USED_THROTTLE_SECONDS) {
            return;
        }
        try {
            app.setLastUsedAt(now);
            openAppRepository.save(app);
        } catch (Exception e) {
            log.debug("更新 lastUsedAt 失败（忽略）: appName={}, msg={}", app.getAppName(), e.getMessage());
        }
    }

    /** 仅 /api/open 端点 */
    private boolean isOpenApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith("/api/open");
    }
}
