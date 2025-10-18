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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ProjectResponse> myProjects() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AdminUser user = userRepo.findByUsername(auth.getName()).orElse(null);
        List<Project> projects;
        if (user != null && "SUPER".equalsIgnoreCase(user.getRole())) {
            projects = projectService.list();
        } else {
            List<ProjectPermission> list = user == null ? List.of() : permRepo.findByUser(user);
            projects = new ArrayList<>();
            for (ProjectPermission pp : list) {
                projects.add(pp.getProject());
            }
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
}

