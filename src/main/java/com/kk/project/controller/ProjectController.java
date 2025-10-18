package com.kk.project.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.dto.CreateProjectRequest;
import com.kk.project.dto.ProjectResponse;
import com.kk.project.dto.UpdateProjectRequest;
import com.kk.project.entity.Project;
import com.kk.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping
    @PreAuthorize("hasRole('SUPER')")
    public ProjectResponse create(@RequestBody CreateProjectRequest req) {
        Project p = projectService.create(req);
        List<String> types = projectService.parseTypes(p);
        Object expected = null;
        try {
            expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
        } catch (Exception ignored) {}
        return ProjectResponse.from(p, types, expected);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
        Project p = projectService.get(id);
        List<String> types = projectService.parseTypes(p);
        Object expected = null;
        try {
            expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
        } catch (Exception ignored) {}
        return ProjectResponse.from(p, types, expected);
    }

    @GetMapping
    public List<ProjectResponse> list() {
        List<Project> list = projectService.list();
        java.util.ArrayList<ProjectResponse> out = new java.util.ArrayList<>();
        for (Project p : list) {
            List<String> types = projectService.parseTypes(p);
            Object expected = null;
            try {
                expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
            } catch (Exception ignored) {}
            out.add(ProjectResponse.from(p, types, expected));
        }
        return out;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication.name, #id)")
    public ProjectResponse update(@PathVariable Long id, @RequestBody UpdateProjectRequest req) {
        Project p = projectService.update(id, req);
        List<String> types = projectService.parseTypes(p);
        Object expected = null;
        try {
            expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
        } catch (Exception ignored) {}
        return ProjectResponse.from(p, types, expected);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER')")
    public void delete(@PathVariable Long id) {
        projectService.delete(id);
    }
}
