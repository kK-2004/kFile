package com.kk.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.common.service.AppConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {
    private final AppConfigService appConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Value("${app.project.monthly-limit.user:3}")
    private int defaultMonthlyLimit;

    @GetMapping
    @PreAuthorize("hasRole('SUPER')")
    public Map<String, Object> get() {
        Integer monthly = appConfigService.getInt(AppConfigService.KEY_USER_MONTHLY_LIMIT);
        if (monthly == null) monthly = defaultMonthlyLimit;
        Long totalQuota = appConfigService.getLong(AppConfigService.KEY_USER_TOTAL_QUOTA_BYTES);
        if (totalQuota == null) totalQuota = 1024L * 1024L * 1024L; // 默认 1GB
        java.util.List<String> types = appConfigService.getStringList(AppConfigService.KEY_USER_ALLOWED_FILE_TYPES);
        return Map.of(
                "monthlyLimitUser", monthly,
                "userTotalQuotaBytes", totalQuota,
                "allowedFileTypes", types
        );
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> update(@RequestBody UpdateConfigReq req) {
        if (req.getMonthlyLimitUser() != null && req.getMonthlyLimitUser() < 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "monthlyLimitUser 不能为负数"));
        }
        if (req.getUserTotalQuotaBytes() != null && req.getUserTotalQuotaBytes() < 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "userTotalQuotaBytes 不能为负数"));
        }
        if (req.getMonthlyLimitUser() != null) {
            appConfigService.setRaw(AppConfigService.KEY_USER_MONTHLY_LIMIT, String.valueOf(req.getMonthlyLimitUser()));
        }
        if (req.getUserTotalQuotaBytes() != null) {
            appConfigService.setRaw(AppConfigService.KEY_USER_TOTAL_QUOTA_BYTES, String.valueOf(req.getUserTotalQuotaBytes()));
        }
        if (req.getAllowedFileTypes() != null) {
            try {
                String json = objectMapper.writeValueAsString(req.getAllowedFileTypes());
                appConfigService.setRaw(AppConfigService.KEY_USER_ALLOWED_FILE_TYPES, json);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("message", "allowedFileTypes 非法"));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @Data
    public static class UpdateConfigReq {
        private Integer monthlyLimitUser; // 每月新建项目上限（USER）
        private Long userTotalQuotaBytes;    // USER总存储配额（字节）
        private List<String> allowedFileTypes; // USER可用文件扩展名白名单
    }
}
