package com.kk.security.controller;

import com.kk.security.service.UserAccountService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserManageController {
    private final UserAccountService userAccountService;

    @PostMapping("/promote")
    @PreAuthorize("hasAuthority('ROLE_SITE_ADMIN') or hasAuthority('ROLE_KF_SUPER')")
    public ResponseEntity<?> promote(@RequestBody ChangeRoleRequest req) {
        if (req.getExternalId() == null || req.getExternalId().isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "externalId 不能为空"));
        }
        String role = req.getRole();
        if (!java.util.List.of("KF_ADMIN", "KF_USER").contains(role)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "仅支持设置 KF_ADMIN 或 KF_USER"));
        }
        try {
            userAccountService.changeRole(Long.parseLong(req.getExternalId()), role);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "externalId 必须为数字"));
        }
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @Data
    public static class ChangeRoleRequest {
        private String externalId;
        private String role; // KF_ADMIN / KF_USER
    }
}
