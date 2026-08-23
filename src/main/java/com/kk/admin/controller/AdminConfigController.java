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
    private final com.kk.storage.StorageBrowserRegistry storageBrowserRegistry;
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
        String kmessageGroupId = appConfigService.getRaw(AppConfigService.KEY_KMESSAGE_GROUP_ID);
        String openApiDefaultSource = appConfigService.getRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE);
        if (openApiDefaultSource == null || openApiDefaultSource.isBlank()) openApiDefaultSource = "oss";
        java.util.List<java.util.Map<String, Object>> roadmap = appConfigService.getObjectList(AppConfigService.KEY_HERO_ROADMAP);
        java.util.List<String> mcpRedirectSchemes = appConfigService.getStringList(AppConfigService.KEY_MCP_REDIRECT_ALLOWED_SCHEMES);
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("monthlyLimitUser", monthly);
        resp.put("userTotalQuotaBytes", totalQuota);
        resp.put("allowedFileTypes", types);
        resp.put("kmessageGroupId", kmessageGroupId);
        resp.put("openApiDefaultSource", openApiDefaultSource);
        resp.put("roadmapItems", roadmap);
        resp.put("mcpRedirectSchemes", mcpRedirectSchemes);
        return resp;
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
        if (req.getRoadmapItems() != null) {
            // 校验：每项必须有 status/statusText/title/desc；status 限定枚举
            for (java.util.Map<String, Object> item : req.getRoadmapItems()) {
                String status = item.get("status") == null ? "" : String.valueOf(item.get("status")).trim();
                if (!"done".equals(status) && !"developing".equals(status) && !"planned".equals(status)) {
                    return ResponseEntity.badRequest().body(Map.of("message", "roadmap status 必须为 done/developing/planned"));
                }
            }
            try {
                String json = objectMapper.writeValueAsString(req.getRoadmapItems());
                appConfigService.setRaw(AppConfigService.KEY_HERO_ROADMAP, json);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("message", "roadmapItems 非法"));
            }
        }
        if (req.getMcpRedirectSchemes() != null) {
            // MCP OAuth 自定义 redirect scheme 白名单（本地 agent 回调，如 workbuddy）。http/https 始终允许。
            try {
                String json = objectMapper.writeValueAsString(req.getMcpRedirectSchemes());
                appConfigService.setRaw(AppConfigService.KEY_MCP_REDIRECT_ALLOWED_SCHEMES, json);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("message", "mcpRedirectSchemes 非法"));
            }
        }
        if (req.getKmessageGroupId() != null) {
            String v = req.getKmessageGroupId().trim();
            appConfigService.setRaw(AppConfigService.KEY_KMESSAGE_GROUP_ID, v.isEmpty() ? null : v);
        }
        if (req.getOpenApiDefaultSource() != null) {
            String v = req.getOpenApiDefaultSource().trim();
            if (!v.isEmpty()) {
                try {
                    storageBrowserRegistry.get(v);
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of("message", "未知或未启用的数据源: " + v));
                }
                appConfigService.setRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE, v);
            } else {
                // 清空 = 恢复默认 oss
                appConfigService.setRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE, null);
            }
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @Data
    public static class UpdateConfigReq {
        private Integer monthlyLimitUser; // 每月新建项目上限（USER）
        private Long userTotalQuotaBytes;    // USER总存储配额（字节）
        private List<String> allowedFileTypes; // USER可用文件扩展名白名单
        private List<String> mcpRedirectSchemes; // MCP OAuth 自定义 redirect scheme 白名单（如 workbuddy）
        private String kmessageGroupId; // kMessage 默认接收飞书群 groupId
        private String openApiDefaultSource; // 开放 API 默认数据源 sourceId（空=恢复默认 oss）
        private List<java.util.Map<String, Object>> roadmapItems; // 首页 Hero 产品路线图（status/statusText/title/desc）
    }
}
