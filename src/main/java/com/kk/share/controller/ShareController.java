package com.kk.share.controller;

import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.share.service.ShareLinkService;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class ShareController {

    private final ShareLinkRepository shareLinkRepository;
    private final ShareLinkService shareLinkService;

    public ShareController(ShareLinkRepository shareLinkRepository, ShareLinkService shareLinkService) {
        this.shareLinkRepository = shareLinkRepository;
        this.shareLinkService = shareLinkService;
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
