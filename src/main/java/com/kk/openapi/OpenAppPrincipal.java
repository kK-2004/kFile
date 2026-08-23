package com.kk.openapi;

/**
 * 开放 API 应用身份（principal）。与 AdminUser 会话体系隔离：
 * 仅携带 ROLE_OPEN_APP，只能在 /api/open/** 链内使用。
 */
public record OpenAppPrincipal(Long id, String appName) {

    public Long getId() {
        return id;
    }

    public String getAppName() {
        return appName;
    }
}
