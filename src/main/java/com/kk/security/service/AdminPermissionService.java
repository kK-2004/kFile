package com.kk.security.service;

import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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

    public boolean canManageProject(Authentication authentication, Long projectId) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        // SUPER 直接放行（包含站点 ADMIN 被映射的 ROLE_SUPER）
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if ("ROLE_SUPER".equals(ga.getAuthority())) return true;
        }
        Project p = projectRepo.findById(projectId).orElse(null);
        if (p == null) return false;

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            String sub = jwtAuth.getToken().getSubject();
            try {
                Long uid = Long.parseLong(sub);
                return permRepo.findByProjectIdAndSiteUserId(projectId, uid).isPresent();
            } catch (Exception e) {
                return false;
            }
        }
        // 本地管理员（用户名口令登录）
        String username = authentication.getName();
        AdminUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) return false;
        if ("SUPER".equalsIgnoreCase(user.getRole())) return true;
        ProjectPermission perm = permRepo.findByUserAndProject(user, p).orElse(null);
        return perm != null;
    }

    public void grantForSiteUser(Long projectId, Long siteUserId) {
        if (projectId == null || siteUserId == null) throw new IllegalArgumentException("projectId/siteUserId 不能为空");
        if (permRepo.findByProjectIdAndSiteUserId(projectId, siteUserId).isEmpty()) {
            Project p = projectRepo.findById(projectId).orElseThrow();
            ProjectPermission pp = new ProjectPermission();
            pp.setProject(p);
            pp.setSiteUserId(siteUserId);
            permRepo.save(pp);
        }
    }

    public void revokeForSiteUser(Long projectId, Long siteUserId) {
        permRepo.findByProjectIdAndSiteUserId(projectId, siteUserId).ifPresent(permRepo::delete);
    }
}
