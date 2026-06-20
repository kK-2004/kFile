package com.kk.share.controller;

import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.share.service.ShareLinkService;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ShareController {

    private final ShareLinkRepository shareLinkRepository;
    private final ShareLinkService shareLinkService;
    private final StorageBrowserRegistry storageRegistry;

    public ShareController(ShareLinkRepository shareLinkRepository, ShareLinkService shareLinkService,
                           StorageBrowserRegistry storageRegistry) {
        this.shareLinkRepository = shareLinkRepository;
        this.shareLinkService = shareLinkService;
        this.storageRegistry = storageRegistry;
    }

    @PostMapping("/api/admin/projects/{projectId}/share")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #projectId)")
    public ResponseEntity<?> createShare(@PathVariable Long projectId, @RequestBody CreateShareRequest req) {
        try {
            ShareLinkService.CreatedShare share = shareLinkService.create(
                    projectId, req.getFilename(), req.getEntries(), req.getExpireSeconds());
            return ResponseEntity.ok(Map.of("code", share.code(), "expireAt", share.expireAt()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "创建分享链接失败"));
        }
    }

    @GetMapping("/api/share/{code}")
    public ResponseEntity<?> getShare(@PathVariable String code) {
        ShareLink link = shareLinkRepository.findByCode(code).orElse(null);
        if (link == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "分享链接不存在"));
        }
        if (link.getExpireAt() != null && Instant.now().isAfter(link.getExpireAt())) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", "分享链接已过期"));
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(link.getData(), Map.class);
            boolean permanent = link.getExpireAt() == null;
            // 永久分享：entries[].u 在创建时留空，此处按 storageSource+storageKey 现签短时效(600s)URL
            // 非永久：u 是创建时 baked-in 的预签名，直接用
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
            if (entries != null) {
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> e : entries) {
                    Map<String, Object> out = new HashMap<>();
                    String url = (String) e.get("u");
                    if (permanent || url == null || url.isEmpty()) {
                        String source = (String) e.get("storageSource");
                        String key = (String) e.get("storageKey");
                        if (source != null && key != null) {
                            try {
                                StorageBrowserService svc = storageRegistry.get(source);
                                String filename = (String) e.get("f");
                                url = svc.downloadUrl(key, true, 600, filename);
                            } catch (Exception ex) {
                                // 源不可用则保留空 URL
                                url = "";
                            }
                        }
                    }
                    out.put("u", url);
                    out.put("f", e.get("f"));
                    out.put("p", e.getOrDefault("p", ""));
                    out.put("s", e.get("s"));
                    // 过滤掉 storageSource/storageKey（内部字段，不暴露给前端）
                    filtered.add(out);
                }
                data.put("entries", filtered);
            }
            if (link.getExpireAt() != null) {
                data.put("expireAt", link.getExpireAt().toEpochMilli());
            }
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "数据解析失败"));
        }
    }

    @Data
    public static class CreateShareRequest {
        private String filename;
        private List<Map<String, Object>> entries;
        private Long expireSeconds;
    }
}
