package com.kk.project.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.dto.ProjectResponse;
import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.service.ProjectQueryService;
import com.kk.project.service.ProjectService;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
public class AdminProjectController {
    private final ProjectService projectService;
    private final AdminUserRepository userRepo;
    private final ProjectPermissionRepository permRepo;
    private final ProjectRepository projectRepository;
    private final com.kk.project.repo.SubmissionRepository submissionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProjectQueryService projectQueryService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ProjectResponse> myProjects() {
        return projectQueryService.myProjects(SecurityContextHolder.getContext().getAuthentication());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #id)")
    public ProjectResponse getOne(@PathVariable Long id) {
        // Admin GET includes allowedSubmitterList (sensitive)
        return projectQueryService.getOne(id, true);
    }

    // 统计：返回配置了允许提交名单的项目中“尚未提交”的名单
    @GetMapping("/{id}/missing-allowed")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #id)")
    public java.util.Map<String, Object> missingAllowed(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return projectQueryService.missingAllowed(id);
    }

    @GetMapping("/{id}/missing-allowed.csv")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #id)")
    public org.springframework.http.ResponseEntity<byte[]> downloadMissingAllowedCsv(@org.springframework.web.bind.annotation.PathVariable Long id) {
        java.util.Map<String, Object> data = missingAllowed(id);
        if (!Boolean.TRUE.equals(data.get("enabled"))) {
            byte[] empty = ("未配置允许提交名单\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return org.springframework.http.ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=missing.csv")
                    .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body(empty);
        }
        @SuppressWarnings("unchecked") java.util.List<String> keys = (java.util.List<String>) data.get("keys");
        @SuppressWarnings("unchecked") java.util.List<java.util.Map<String,String>> missing = (java.util.List<java.util.Map<String,String>>) data.get("missing");
        StringBuilder sb = new StringBuilder();
        // header
        for (int i = 0; i < keys.size(); i++) { if (i>0) sb.append(','); sb.append(keys.get(i)); }
        sb.append('\n');
        for (java.util.Map<String,String> row : missing) {
            for (int i = 0; i < keys.size(); i++) {
                if (i>0) sb.append(',');
                String v = row.getOrDefault(keys.get(i), "");
                // naive CSV escaping
                if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else { sb.append(v); }
            }
            sb.append('\n');
        }
        byte[] bom = new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF};
        byte[] bytesUtf8 = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + bytesUtf8.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(bytesUtf8, 0, bytes, bom.length, bytesUtf8.length);
        String filename = "project-" + id + "-missing.csv";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(bytes);
    }
}
