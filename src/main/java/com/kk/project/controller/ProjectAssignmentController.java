package com.kk.project.controller;

import com.kk.security.service.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
public class ProjectAssignmentController {
    private final AdminPermissionService adminPermissionService;

    // 为站点用户授予项目管理权限
    @PostMapping("/{projectId}/site-users/{siteUserId}")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> grantForSiteUser(@PathVariable Long projectId, @PathVariable Long siteUserId) {
        adminPermissionService.grantForSiteUser(projectId, siteUserId);
        return ResponseEntity.ok().build();
    }

    // 回收站点用户的项目管理权限
    @DeleteMapping("/{projectId}/site-users/{siteUserId}")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> revokeForSiteUser(@PathVariable Long projectId, @PathVariable Long siteUserId) {
        adminPermissionService.revokeForSiteUser(projectId, siteUserId);
        return ResponseEntity.ok().build();
    }
}

