package com.kk.template.controller;

import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.template.dto.ProjectTemplateRequest;
import com.kk.template.dto.ProjectTemplateResponse;
import com.kk.template.entity.ProjectTemplate;
import com.kk.template.service.ProjectTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/admin/templates")
@RequiredArgsConstructor
public class ProjectTemplateController {
    private final ProjectTemplateService templateService;
    private final AdminUserRepository userRepo;

    @GetMapping
    @PreAuthorize("hasRole('SUPER')")
    public List<ProjectTemplateResponse> listAll() {
        return templateService.findAll().stream().map(ProjectTemplateResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER')")
    public ProjectTemplateResponse create(@RequestBody ProjectTemplateRequest req) {
        Long ownerId = currentUserId();
        ProjectTemplate t = templateService.save(req, ownerId);
        return ProjectTemplateResponse.from(t);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER')")
    public ProjectTemplateResponse update(@PathVariable Long id, @RequestBody ProjectTemplateRequest req) {
        ProjectTemplate t = templateService.update(id, req, currentUserId());
        return ProjectTemplateResponse.from(t);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER')")
    public void delete(@PathVariable Long id) {
        templateService.delete(id, currentUserId());
    }

    /**
     * 当前用户可用的模板（owner=自己 ∪ 被分配给自己的），任意已登录管理员可调。
     * Web 创建项目下拉与 MCP list_my_templates 共用此来源。
     */
    @GetMapping("/usable")
    @PreAuthorize("isAuthenticated()")
    public List<ProjectTemplateResponse> listUsable() {
        AdminUser user = currentUser();
        return templateService.listUsableForUser(user).stream()
                .map(ProjectTemplateResponse::from).toList();
    }

    private AdminUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userRepo.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
    }

    private Long currentUserId() {
        return currentUser().getId();
    }
}
