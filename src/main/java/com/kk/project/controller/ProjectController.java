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
    private final com.kk.security.service.AdminPermissionService adminPermissionService;

    @PostMapping
    @PreAuthorize("hasRole('SUPER') or hasAuthority('ROLE_SITE_USER') or hasAuthority('ROLE_SITE_ADMIN')")
    public ProjectResponse create(@RequestBody CreateProjectRequest req,
                                  org.springframework.security.core.Authentication authentication) {
        Project p = projectService.create(req, authentication);
        // 如果是站点用户，自动授予自己管理权限
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth) {
            String sub = jwtAuth.getToken().getSubject();
            try { adminPermissionService.grantForSiteUser(p.getId(), Long.parseLong(sub)); } catch (Exception ignored) {}
        }
        List<String> types = projectService.parseTypes(p);
        Object expected = null;
        try {
            expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
        } catch (Exception ignored) {}
        return ProjectResponse.from(p, types, expected, false);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
        Project p = projectService.get(id);
        List<String> types = projectService.parseTypes(p);
        Object expected = null;
        try {
            expected = p.getExpectedUserFields() == null ? null : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>(){});
        } catch (Exception ignored) {}
        return ProjectResponse.from(p, types, expected, false);
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
            out.add(ProjectResponse.from(p, types, expected, false));
        }
        return out;
    }

    @GetMapping("/quota")
    @PreAuthorize("hasAuthority('ROLE_SITE_USER') or hasAuthority('ROLE_SITE_ADMIN') or hasRole('SUPER')")
    public java.util.Map<String, Object> quota(org.springframework.security.core.Authentication authentication) {
        return projectService.getCreationQuota(authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #id)")
    public ProjectResponse update(@PathVariable Long id, @RequestBody UpdateProjectRequest req,
                                  org.springframework.security.core.Authentication authentication) {
        Project p = projectService.update(id, req, authentication);
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
