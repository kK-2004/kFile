package com.kk.security.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kk.security.entity.AdminUser;

import java.time.Instant;

/** 用户管理接口的出参视图：不含 password（BCrypt 哈希）等敏感字段。 */
public record AdminUserView(
        Long id,
        String username,
        String role,
        Long quotaBytes,
        Boolean enabled,
        Instant createdAt,
        @JsonSerialize(using = ToStringSerializer.class) // 雪花 long 超出 JS 安全整数，JSON 一律字符串
        Long guid) {

    public static AdminUserView of(AdminUser u) {
        return new AdminUserView(u.getId(), u.getUsername(), u.getRole(), u.getQuotaBytes(),
                u.getEnabled(), u.getCreatedAt(), u.getGuid());
    }
}
