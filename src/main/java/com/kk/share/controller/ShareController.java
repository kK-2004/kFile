package com.kk.share.controller;

import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.util.Base62Util;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ShareController {

    private final ShareLinkRepository shareLinkRepository;

    public ShareController(ShareLinkRepository shareLinkRepository) {
        this.shareLinkRepository = shareLinkRepository;
    }

    @PostMapping("/api/admin/projects/{projectId}/share")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #projectId)")
    public ResponseEntity<?> createShare(@PathVariable Long projectId, @RequestBody CreateShareRequest req) {
        if (req.getEntries() == null || req.getEntries().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "entries 不能为空"));
        }
        long expireSeconds = req.getExpireSeconds() != null && req.getExpireSeconds() > 0
                ? req.getExpireSeconds() : 300;

        String code = Base62Util.encode(UUID.randomUUID());

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String dataJson;
        try {
            dataJson = mapper.writeValueAsString(Map.of(
                    "filename", req.getFilename() != null ? req.getFilename() : "download.zip",
                    "entries", req.getEntries()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "序列化失败"));
        }

        ShareLink link = new ShareLink();
        link.setCode(code);
        link.setProjectId(projectId);
        link.setData(dataJson);
        link.setCreatedAt(Instant.now());
        link.setExpireAt(Instant.now().plusSeconds(expireSeconds));
        shareLinkRepository.save(link);

        return ResponseEntity.ok(Map.of("code", code, "expireAt", link.getExpireAt().toEpochMilli()));
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
