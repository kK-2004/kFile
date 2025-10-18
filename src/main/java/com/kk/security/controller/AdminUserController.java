package com.kk.security.controller;

import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    public AdminUserController(AdminUserRepository userRepo, ProjectRepository projectRepo, ProjectPermissionRepository permRepo, PasswordEncoder encoder) {
        this.userRepo = userRepo;
        this.projectRepo = projectRepo;
        this.permRepo = permRepo;
        this.encoder = encoder;
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
        return userRepo.save(u);
    }

    @PostMapping("/{userId}/projects/{projectId}")
    @PreAuthorize("hasRole('SUPER')")
    public ResponseEntity<?> grant(@PathVariable Long userId, @PathVariable Long projectId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        Project p = projectRepo.findById(projectId).orElseThrow();
        if (permRepo.findByUserAndProject(u, p).isEmpty()) {
            ProjectPermission pp = new ProjectPermission();
            pp.setUser(u); pp.setProject(p);
            permRepo.save(pp);
        }
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
    public List<Long> listUserProjects(@PathVariable Long userId) {
        AdminUser u = userRepo.findById(userId).orElseThrow();
        return permRepo.findByUser(u).stream().map(pp -> pp.getProject().getId()).toList();
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

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('SUPER')")
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
