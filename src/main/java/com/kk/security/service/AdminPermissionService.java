package com.kk.security.service;

import com.kk.project.entity.Project;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import com.kk.project.repo.ProjectRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("adminPermissionService")
public class AdminPermissionService {
    @Autowired
    private AdminUserRepository userRepo;
    @Autowired
    private ProjectRepository projectRepo;
    @Autowired
    private ProjectPermissionRepository permRepo;

    public boolean canCreateProject(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            String authority = ga.getAuthority();
            if ("ROLE_SUPER".equals(authority) || "ROLE_ADMIN".equals(authority)) return true;
        }
        return false;
    }

    public boolean canManageProject(Authentication authentication, Long projectId) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if ("ROLE_SUPER".equals(ga.getAuthority())) return true;
        }
        Project p = projectRepo.findById(projectId).orElse(null);
        if (p == null) return false;
        String username = authentication.getName();
        AdminUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) return false;
        if (isSuperOrOwner(user, p)) return true;
        ProjectPermission perm = permRepo.findByUserAndProject(user, p).orElse(null);
        return perm != null;
    }

    /** 是否允许编辑项目（SUPER/创建者全 true；ADMIN 需 ProjectPermission.canEdit） */
    public boolean canEditProject(Authentication authentication, Long projectId) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if ("ROLE_SUPER".equals(ga.getAuthority())) return true;
        }
        Project p = projectRepo.findById(projectId).orElse(null);
        if (p == null) return false;
        AdminUser user = userRepo.findByUsername(authentication.getName()).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) return false;
        if (isSuperOrOwner(user, p)) return true;
        ProjectPermission perm = permRepo.findByUserAndProject(user, p).orElse(null);
        return perm != null && perm.isCanEdit();
    }

    /** 是否允许删除项目（SUPER/创建者全 true；ADMIN 需 ProjectPermission.canDelete） */
    public boolean canDeleteProject(Authentication authentication, Long projectId) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if ("ROLE_SUPER".equals(ga.getAuthority())) return true;
        }
        Project p = projectRepo.findById(projectId).orElse(null);
        if (p == null) return false;
        AdminUser user = userRepo.findByUsername(authentication.getName()).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) return false;
        if (isSuperOrOwner(user, p)) return true;
        ProjectPermission perm = permRepo.findByUserAndProject(user, p).orElse(null);
        return perm != null && perm.isCanDelete();
    }

    /** SUPER 或项目创建者（ownerUserId，仅 ADMIN 创建时有值）直接全权；调用方需保证 user 非空 */
    private boolean isSuperOrOwner(AdminUser user, Project p) {
        return "SUPER".equalsIgnoreCase(user.getRole())
                || user.getId().equals(p.getOwnerUserId());
    }
}
