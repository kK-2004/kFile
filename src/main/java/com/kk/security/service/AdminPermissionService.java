package com.kk.security.service;

import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
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

    public boolean canManageProject(String username, Long projectId) {
        AdminUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) return false;
        if ("SUPER".equalsIgnoreCase(user.getRole())) return true;
        Project p = projectRepo.findById(projectId).orElse(null);
        if (p == null) return false;
        ProjectPermission perm = permRepo.findByUserAndProject(user, p).orElse(null);
        return perm != null;
    }
}

