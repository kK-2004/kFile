package com.kk.admin.controller;

import com.kk.admin.task.DeleteProjectTaskService;
import com.kk.project.service.ProjectService;
import com.kk.security.service.AdminPermissionService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminTaskController {
    private final DeleteProjectTaskService deleteTaskService;
    private final com.kk.admin.task.ArchiveTaskService archiveTaskService;
    private final AdminPermissionService adminPermissionService;
    private final ProjectService projectService;

    @PostMapping("/projects/{projectId}/delete-task")
    @PreAuthorize("hasRole('SUPER')")
    public Map<String,Object> startDelete(@PathVariable Long projectId) {
        var t = deleteTaskService.start(projectId);
        return java.util.Map.of("taskId", t.getId(), "status", t.getStatus());
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("isAuthenticated()")
    public java.util.Map<String,Object> get(@PathVariable String taskId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        var d = deleteTaskService.get(taskId);
        if (d != null) {
            if (!adminPermissionService.canManageProject(auth, d.getProjectId())) {
                throw new AccessDeniedException("Access denied");
            }
            return java.util.Map.of(
                    "id", d.getId(),
                    "type", "DELETE",
                    "projectId", d.getProjectId(),
                    "projectName", d.getProjectName(),
                    "status", d.getStatus(),
                    "totalFiles", d.getTotalFiles(),
                    "deletedFiles", d.getDeletedFiles(),
                    "message", d.getMessage(),
                    "startedAt", d.getStartedAt(),
                    "endedAt", d.getEndedAt()
            );
        }
        var a = archiveTaskService.get(taskId);
        if (a == null) throw new IllegalArgumentException("Task not found: " + taskId);
        if (!adminPermissionService.canManageProject(auth, a.getProjectId())) {
            throw new AccessDeniedException("Access denied");
        }
        java.util.Map<String,Object> map = new java.util.HashMap<>();
        map.put("id", a.getId());
        map.put("type", "ARCHIVE");
        map.put("projectId", a.getProjectId());
        map.put("projectName", a.getProjectName());
        map.put("status", a.getStatus());
        map.put("totalEntries", a.getTotalEntries());
        map.put("processedEntries", a.getProcessedEntries());
        map.put("bytesWritten", a.getBytesWritten());
        map.put("filename", a.getFilename());
        map.put("message", a.getMessage());
        map.put("startedAt", a.getStartedAt());
        map.put("endedAt", a.getEndedAt());
        return map;
    }

    @PostMapping("/projects/{projectId}/archive-task")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #projectId)")
    public java.util.Map<String,Object> startArchive(@PathVariable Long projectId,
                                                     @RequestBody(required = false) java.util.Map<String,Object> body) {
        String fieldKey = body == null ? null : String.valueOf(body.getOrDefault("fieldKey", "")).trim();
        String fieldValue = body == null ? null : String.valueOf(body.getOrDefault("fieldValue", "")).trim();
        var t = archiveTaskService.start(projectId,
                (fieldKey != null && !fieldKey.isEmpty()) ? fieldKey : null,
                (fieldValue != null && !fieldValue.isEmpty()) ? fieldValue : null);
        return java.util.Map.of("taskId", t.getId(), "status", t.getStatus(), "filename", t.getFilename());
    }

    // 前端打包 ZIP：后端仅返回清单（OSS 预签名直链 + 文件名）
    @GetMapping("/projects/{projectId}/archive-manifest")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #projectId)")
    public java.util.Map<String,Object> archiveManifest(@PathVariable Long projectId,
                                                        @RequestParam(required = false) String fieldKey,
                                                        @RequestParam(required = false) String fieldValue,
                                                        @RequestParam(required = false, defaultValue = "3600") long expireSeconds) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!adminPermissionService.canManageProject(auth, projectId)) {
            throw new AccessDeniedException("Access denied");
        }
        String fk = fieldKey == null ? null : fieldKey.trim();
        String fv = fieldValue == null ? null : fieldValue.trim();
        if (fk != null && fk.isEmpty()) fk = null;
        if (fv != null && fv.isEmpty()) fv = null;
        long exp = expireSeconds <= 0 ? 3600 : expireSeconds;
        java.util.List<com.kk.admin.task.ArchiveTaskService.ManifestEntry> entries;
        try {
            entries = archiveTaskService.buildManifest(projectId, fk, fv, exp);
        } catch (Exception e) {
            throw new IllegalStateException("生成打包清单失败: " + e.getMessage(), e);
        }
        // Map.of 不允许 null，这里改为可接受 null 的可变 Map
        java.util.Map<String,Object> map = new java.util.HashMap<>();
        map.put("projectId", projectId);
        map.put("fieldKey", fk);
        map.put("fieldValue", fv);
        map.put("expireSeconds", exp);
        map.put("totalEntries", entries.size());
        map.put("entries", entries);
        com.kk.project.entity.Project p = projectService.get(projectId);
        String baseName = (p.getName() == null || p.getName().isBlank()) ? ("project-" + projectId) : p.getName().trim();
        if (fk != null && !fk.isBlank() && fv != null && !fv.isBlank()) {
            baseName = baseName + "-" + fk + "-" + fv;
        }
        map.put("filename", baseName + ".zip");
        return map;
    }

    @GetMapping("/tasks/{taskId}/download")
    @PreAuthorize("isAuthenticated()")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String taskId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        var t = archiveTaskService.get(taskId);
        if (t == null || t.getFilePath() == null || !"COMPLETED".equalsIgnoreCase(t.getStatus())) {
            throw new IllegalStateException("Task not completed or not found");
        }
        if (!adminPermissionService.canManageProject(auth, t.getProjectId())) {
            throw new AccessDeniedException("Access denied");
        }
        var res = archiveTaskService.file(taskId);
        String name = t.getFilename() == null ? ("archive-" + taskId + ".zip") : t.getFilename();
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_");
        String encoded;
        try { encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"); }
        catch (Exception e) { encoded = ascii; }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/zip")
                .contentLength(res.getFile().length())
                .body(res);
    }
}
