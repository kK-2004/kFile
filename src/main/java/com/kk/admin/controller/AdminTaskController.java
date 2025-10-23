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

    @PostMapping("/projects/{projectId}/delete-task")
    @PreAuthorize("hasRole('SUPER')")
    public Map<String,Object> startDelete(@PathVariable Long projectId) {
        var t = deleteTaskService.start(projectId);
        return java.util.Map.of("taskId", t.getId(), "status", t.getStatus());
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasRole('SUPER')")
    public DeleteProjectTaskService.Task get(@PathVariable String taskId) {
        var t = deleteTaskService.get(taskId);
        if (t == null) throw new IllegalArgumentException("Task not found: " + taskId);
        return t;
    }
}

