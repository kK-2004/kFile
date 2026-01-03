package com.kk.common.logging;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.StringJoiner;

public final class AuditLogUtil {
    private AuditLogUtil() {}

    public static Authentication currentAuth() {
        try {
            return SecurityContextHolder.getContext() == null ? null : SecurityContextHolder.getContext().getAuthentication();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String actor(Authentication auth) {
        if (auth == null) return "anonymous";
        if (auth instanceof JwtAuthenticationToken jwt) {
            String sub = null;
            try { sub = jwt.getToken() == null ? null : jwt.getToken().getSubject(); } catch (Exception ignored) {}
            String name = safe(auth.getName());
            return "jwt:" + (sub == null ? name : sub);
        }
        return safe(auth.getName());
    }

    public static String roles(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return "";
        StringJoiner sj = new StringJoiner(",");
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a == null || a.getAuthority() == null) continue;
            sj.add(a.getAuthority());
        }
        return sj.toString();
    }

    public static String safe(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }
}

