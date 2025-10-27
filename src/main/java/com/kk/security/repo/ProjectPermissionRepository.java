package com.kk.security.repo;

import com.kk.project.entity.Project;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectPermissionRepository extends JpaRepository<ProjectPermission, Long> {
    Optional<ProjectPermission> findByUserAndProject(AdminUser user, Project project);
    List<ProjectPermission> findByUser(AdminUser user);
    Optional<ProjectPermission> findByProjectIdAndSiteUserId(Long projectId, Long siteUserId);
    List<ProjectPermission> findBySiteUserId(Long siteUserId);
    void deleteBySiteUserId(Long siteUserId);
    void deleteByUser(AdminUser user);
}
