package com.kk.security.controller;

import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import com.kk.template.entity.ProjectTemplate;
import com.kk.template.service.ProjectTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminUserRepository userRepo;
    private final ProjectRepository projectRepo;
    private final ProjectPermissionRepository permRepo;
    private final PasswordEncoder encoder;
    private final ProjectTemplateService templateService;

    public AdminUserController(AdminUserRepository userRepo, ProjectRepository projectRepo,
                               ProjectPermissionRepository permRepo, PasswordEncoder encoder,
                               ProjectTemplateService templateService) {
        this.userRepo = userRepo;
        this.projectRepo = projectRepo;
        this.permRepo = permRepo;
        this.encoder = encoder;
        this.templateService = templateService;
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER')")
    public List<AdminUser> list() { return userRepo.findAll(); }

    @PostMapping
    @PreAuthorize("hasRole('SUPER')")
    public AdminUser create(@RequestBody Map<String, Object> req) {
        String username = (String) req.get("username");
        String password = (String) req.get("password");
        String role = (String) req.getOrDefault("role", "ADMIN");
        AdminUser u = new AdminUser();
        u.setUsername(username);
        u.setPassword(encoder.encode(password));
        u.setRole(role);
        u.setEnabled(true);
        // 可选：创建时设配额（字节）；前端传 GB 会转字节
        Object quota = req.get("quotaBytes");
        if (quota instanceof Number n) u.setQuotaBytes(n.longValue());
        return userRepo.save(u);
    }

    /** 更新用户配额（SUPER 可设；传 null=用全局默认，0=不限） */
    @PutMapping("/{userId}/quota")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> setQuota(@PathVariable Long userId, @RequestBody Map<String, Object> req) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        Object quota = req.get("quotaBytes");
        if (quota == null) u.setQuotaBytes(null);
        else if (quota instanceof Number n) u.setQuotaBytes(n.longValue());
        userRepo.save(u);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{userId}/projects/{projectId}")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> grant(@PathVariable Long userId, @PathVariable Long projectId,
                                   @RequestBody(required = false) java.util.Map<String, Object> body) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        Project p = projectRepo.findById(projectId).orElseThrow();
        boolean canEdit = body != null && Boolean.TRUE.equals(body.get("canEdit"));
        boolean canDelete = body != null && Boolean.TRUE.equals(body.get("canDelete"));
        ProjectPermission pp = permRepo.findByUserAndProject(u, p).orElse(null);
        if (pp == null) {
            pp = new ProjectPermission();
            pp.setUser(u); pp.setProject(p);
        }
        // SUPER 分配权限时细化 canEdit/canDelete；已有权限则更新
        pp.setCanEdit(canEdit);
        pp.setCanDelete(canDelete);
        permRepo.save(pp);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{userId}/projects/{projectId}")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> revoke(@PathVariable Long userId, @PathVariable Long projectId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        Project p = projectRepo.findById(projectId).orElseThrow();
        permRepo.findByUserAndProject(u, p).ifPresent(permRepo::delete);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{userId}/projects")
    @PreAuthorize("hasRole('SUPER')")
    public List<java.util.Map<String, Object>> listUserProjects(@PathVariable Long userId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        return permRepo.findByUser(u).stream()
                .map(pp -> java.util.Map.<String, Object>of(
                        "projectId", pp.getProject().getId(),
                        "canEdit", pp.isCanEdit(),
                        "canDelete", pp.isCanDelete()))
                .toList();
    }

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> resetPassword(@PathVariable Long userId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        String tmp = genTempPassword();
        u.setPassword(encoder.encode(tmp));
        userRepo.save(u);
        return ResponseEntity.ok(Map.of("newPassword", tmp));
    }

    private String genTempPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    // ===== 模板使用授权（仅 SUPER）=====

    @PostMapping("/{userId}/templates/{templateId}")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> grantTemplate(@PathVariable Long userId, @PathVariable Long templateId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        ProjectTemplate t = templateService.get(templateId);
        templateService.grant(u, t);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{userId}/templates/{templateId}")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> revokeTemplate(@PathVariable Long userId, @PathVariable Long templateId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        ProjectTemplate t = templateService.get(templateId);
        templateService.revoke(u, t);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{userId}/templates")
    @PreAuthorize("hasRole('SUPER')")
    public List<Long> listUserTemplates(@PathVariable Long userId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        return templateService.listAssignedTemplateIds(u);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('SUPER')")
    @Transactional
    public ResponseEntity<?> deleteUser(@PathVariable Long userId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        if ("SUPER".equalsIgnoreCase(u.getRole())) {
            return ResponseEntity.badRequest().body(Map.of("message", "不能删除SUPER账号"));
        }
        // 删除其项目权限
        permRepo.deleteByUser(u);
        userRepo.delete(u);
        return ResponseEntity.ok().build();
    }
}
