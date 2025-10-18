package com.kk.common;

import jakarta.servlet.http.HttpServletRequest;

public class WebClientInfoUtil {
    public static String getClientIp(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_CLIENT_IP",
                "HTTP_X_FORWARDED_FOR"
        };
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && v.length() != 0 && !"unknown".equalsIgnoreCase(v)) {
                // may contain a list: client, proxy1, proxy2
                int comma = v.indexOf(',');
                return comma > 0 ? v.substring(0, comma).trim() : v.trim();
            }
        }
        String addr = request.getRemoteAddr();
        return addr == null ? "" : addr;
    }
}

