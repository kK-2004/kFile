package com.kk.admin.controller;

import com.kk.admin.task.DeleteProjectTaskService;
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

    @PostMapping("/projects/{projectId}/delete-task")
    @PreAuthorize("hasRole('SUPER')")
    public Map<String,Object> startDelete(@PathVariable Long projectId) {
        var t = deleteTaskService.start(projectId);
        return java.util.Map.of("taskId", t.getId(), "status", t.getStatus());
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasRole('SUPER')")
    public java.util.Map<String,Object> get(@PathVariable String taskId) {
        var d = deleteTaskService.get(taskId);
        if (d != null) {
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
    @PreAuthorize("hasRole('SUPER')")
    public java.util.Map<String,Object> startArchive(@PathVariable Long projectId,
                                                     @RequestBody(required = false) java.util.Map<String,Object> body) {
        String fieldKey = body == null ? null : String.valueOf(body.getOrDefault("fieldKey", "")).trim();
        String fieldValue = body == null ? null : String.valueOf(body.getOrDefault("fieldValue", "")).trim();
        var t = archiveTaskService.start(projectId,
                (fieldKey != null && !fieldKey.isEmpty()) ? fieldKey : null,
                (fieldValue != null && !fieldValue.isEmpty()) ? fieldValue : null);
        return java.util.Map.of("taskId", t.getId(), "status", t.getStatus(), "filename", t.getFilename());
    }

    @GetMapping("/tasks/{taskId}/download")
    @PreAuthorize("hasRole('SUPER')")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String taskId) {
        var t = archiveTaskService.get(taskId);
        if (t == null || t.getFilePath() == null || !"COMPLETED".equalsIgnoreCase(t.getStatus())) {
            throw new IllegalStateException("Task not completed or not found");
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
