package com.kk.security.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @jakarta.annotation.Resource
    private org.springframework.core.env.Environment env;
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 提示前端跳转登录页（需考虑子路径部署）
        String base = env == null ? "" : env.getProperty("app.base-path", "");
        String prefix = normalizeBase(base);
        response.setHeader("X-Redirect", prefix + "/admin/login");
        response.getWriter().write("{\"message\":\"无权限，请登录\"}");
    }

    private String normalizeBase(String base) {
        if (base == null || base.isBlank()) return "";
        String b = base.trim();
        if (!b.startsWith("/")) b = "/" + b;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if ("/".equals(b)) return "";
        return b;
    }
}
