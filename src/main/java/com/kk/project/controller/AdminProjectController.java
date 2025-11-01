package com.kk.project.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.dto.ProjectResponse;
import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
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

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ProjectResponse> myProjects() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<Project> projects;
        boolean isSuper = auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER".equals(a.getAuthority()));
        if (isSuper) {
            projects = projectService.list();
        } else if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth) {
            String sub = jwtAuth.getToken().getSubject();
            java.util.List<ProjectPermission> list;
            try {
                list = permRepo.findBySiteUserId(Long.parseLong(sub));
            } catch (Exception e) {
                list = java.util.List.of();
            }
            projects = new ArrayList<>();
            for (ProjectPermission pp : list) projects.add(pp.getProject());
        } else {
            AdminUser user = userRepo.findByUsername(auth.getName()).orElse(null);
            List<ProjectPermission> list = user == null ? List.of() : permRepo.findByUser(user);
            projects = new ArrayList<>();
            for (ProjectPermission pp : list) projects.add(pp.getProject());
        }
        List<ProjectResponse> out = new ArrayList<>();
        for (Project p : projects) {
            List<String> types = projectService.parseTypes(p);
            Object expected = null;
            try {
                expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
            } catch (Exception ignored) {}
            out.add(ProjectResponse.from(p, types, expected));
        }
        return out;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #id)")
    public ProjectResponse getOne(@PathVariable Long id) {
        Project p = projectService.get(id);
        List<String> types = projectService.parseTypes(p);
        Object expected = null;
        try {
            expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
        } catch (Exception ignored) {}
        // Admin GET includes allowedSubmitterList (sensitive)
        return ProjectResponse.from(p, types, expected, true);
    }

    // 统计：返回配置了允许提交名单的项目中“尚未提交”的名单
    @GetMapping("/{id}/missing-allowed")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #id)")
    public java.util.Map<String, Object> missingAllowed(@org.springframework.web.bind.annotation.PathVariable Long id) {
        Project p = projectService.get(id);
        java.util.List<String> keys;
        Object rawList;
        try {
            keys = p.getAllowedSubmitterKeys() == null ? java.util.List.of() : objectMapper.readValue(p.getAllowedSubmitterKeys(), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){});
        } catch (Exception e) { keys = java.util.List.of(); }
        try {
            rawList = p.getAllowedSubmitterList() == null ? null : objectMapper.readValue(p.getAllowedSubmitterList(), Object.class);
        } catch (Exception e) { rawList = null; }
        if (keys == null || keys.isEmpty() || rawList == null) {
            return java.util.Map.of("enabled", false, "message", "未配置允许提交名单");
        }

        java.util.Set<String> allowedTokens = new java.util.LinkedHashSet<>();
        boolean composite = keys.size() > 1;
        if (!composite) {
            String k = keys.get(0);
            if (rawList instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (o == null) continue;
                    if (o instanceof String s) {
                        String v = s.trim(); if (!v.isEmpty()) allowedTokens.add(v);
                    } else if (o instanceof java.util.Map<?,?> m) {
                        Object v = m.get(k); if (v != null) { String s = String.valueOf(v).trim(); if (!s.isEmpty()) allowedTokens.add(s); }
                    }
                }
            }
        } else {
            if (rawList instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof java.util.Map<?,?> m)) continue;
                    java.util.List<String> vals = new java.util.ArrayList<>();
                    boolean miss = false;
                    for (String k : keys) {
                        Object v = m.get(k);
                        String s = v == null ? "" : String.valueOf(v).trim();
                        if (s.isEmpty()) { miss = true; break; }
                        vals.add(s);
                    }
                    if (!miss) allowedTokens.add(String.join("\u0001", vals));
                }
            }
        }

        // 收集已提交的 token
        java.util.Set<String> submittedTokens = new java.util.HashSet<>();
        java.util.List<com.kk.project.entity.Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(p);
        for (com.kk.project.entity.Submission s : all) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                if (node == null) continue;
                if (!composite) {
                    String k = keys.get(0);
                    String v = node.has(k) && !node.get(k).isNull() ? node.get(k).asText("").trim() : "";
                    if (!v.isEmpty()) submittedTokens.add(v);
                } else {
                    java.util.List<String> vals = new java.util.ArrayList<>();
                    boolean miss = false;
                    for (String k : keys) {
                        String v = node.has(k) && !node.get(k).isNull() ? node.get(k).asText("").trim() : "";
                        if (v.isEmpty()) { miss = true; break; }
                        vals.add(v);
                    }
                    if (!miss) submittedTokens.add(String.join("\u0001", vals));
                }
            } catch (Exception ignored) {}
        }

        java.util.List<java.util.Map<String, String>> missing = new java.util.ArrayList<>();
        for (String t : allowedTokens) {
            if (submittedTokens.contains(t)) continue;
            if (!composite) {
                missing.add(java.util.Map.of(keys.get(0), t));
            } else {
                String[] parts = t.split("\u0001", -1);
                java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                for (int i = 0; i < keys.size() && i < parts.length; i++) m.put(keys.get(i), parts[i]);
                missing.add(m);
            }
        }
        return java.util.Map.of(
                "enabled", true,
                "keys", keys,
                "composite", composite,
                "missingCount", missing.size(),
                "missing", missing
        );
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
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "project-" + id + "-missing.csv";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(bytes);
    }
}
