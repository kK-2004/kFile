package com.kk.security.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Web / OAuth consent 链上的网关 Bearer 认证。
 *
 * <p>带 {@code Authorization: Bearer} 时只认网关 token（不回落会话）：通过则以对应 kfile 账号
 * （principal=username，authorities=ROLE_SUPER/ROLE_ADMIN）建立本次请求的 SecurityContext，不创建 session，
 * 既有按用户名/角色的权限逻辑零改动复用。失败直接返回 JSON：
 * <ul>
 *   <li>401 {@code GATEWAY_TOKEN_INVALID}：签名/过期/aud/已被踢下线，前端应刷新 token 或重新登录；</li>
 *   <li>403 {@code ACCOUNT_NOT_PROVISIONED} / {@code ACCOUNT_DISABLED} / {@code ACCOUNT_CONFLICT}：网关登录有效但 kfile 账号不可用。</li>
 * </ul>
 * 未带 Bearer 时不处理（公开接口、应急登录的本地会话照常工作）。
 */
@Component
@RequiredArgsConstructor
public class GatewayBearerAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GatewayAuthProperties props;
    private final GatewayTokenVerifier verifier;
    private final GatewayUserResolver userResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (!props.isEnabled() || header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        var identity = verifier.verify(header.substring(BEARER_PREFIX.length()).trim()).orElse(null);
        if (identity == null) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "GATEWAY_TOKEN_INVALID", "登录已失效，请重新登录");
            return;
        }
        switch (userResolver.resolve(identity)) {
            case GatewayUserResolver.Bound bound -> {
                String role = bound.user().getRole() == null ? "" : bound.user().getRole().toUpperCase();
                var auth = new UsernamePasswordAuthenticationToken(bound.user().getUsername(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            }
            case GatewayUserResolver.NotProvisioned ignored -> write(response, HttpServletResponse.SC_FORBIDDEN,
                    "ACCOUNT_NOT_PROVISIONED", "当前账号「" + identity.username() + "」未开通 kFile，请联系管理员");
            case GatewayUserResolver.Disabled ignored -> write(response, HttpServletResponse.SC_FORBIDDEN,
                    "ACCOUNT_DISABLED", "当前 kFile 账号已停用，请联系管理员");
            case GatewayUserResolver.Conflict ignored -> write(response, HttpServletResponse.SC_FORBIDDEN,
                    "ACCOUNT_CONFLICT", "kFile 中同名账号已绑定其他统一认证用户，请联系管理员");
        }
    }

    private static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(Map.of("code", code, "message", message)));
    }
}
