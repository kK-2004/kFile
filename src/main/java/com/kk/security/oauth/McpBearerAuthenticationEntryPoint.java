package com.kk.security.oauth;

import com.kk.config.McpOAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * MCP Bearer 401 challenge（任务 3.3）。
 *
 * <p>当 MCP 请求缺失、无效、过期、吊销或 resource 不匹配的 token 时，返回 {@code 401} 并统一设置含
 * {@code resource_metadata} 与 {@code scope="mcp:tools"} 的 Bearer {@code WWW-Authenticate} header。
 *
 * <p>该 challenge 只用于 {@code /mcp}（MCP resource server）链，使兼容 agent 能从 {@code 401} 自动发现
 * 授权服务并完成 OAuth。WWW-Authenticate 中的 URL 全部来自可信 {@code app.public-base-url}。
 */
@RequiredArgsConstructor
public class McpBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final McpOAuthProperties props;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        // WWW-Authenticate: Bearer resource_metadata="<...>", error="invalid_token", scope="mcp:tools"
        String challenge =
                "Bearer resource_metadata=\"" + props.protectedResourceMetadataUrl() + "\","
                        + " error=\"invalid_token\","
                        + " error_description=\"The access token is missing, expired, revoked or "
                        + "does not match the MCP resource\","
                        + " scope=\"" + props.getScope() + "\"";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", challenge);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"invalid_token\"}");
    }
}
