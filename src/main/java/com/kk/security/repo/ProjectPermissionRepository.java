package com.kk.security.repo;

import com.kk.project.entity.Project;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectPermissionRepository extends JpaRepository<ProjectPermission, Long> {
    Optional<ProjectPermission> findByUserAndProject(AdminUser user, Project project);
    List<ProjectPermission> findByUser(AdminUser user);
    Optional<ProjectPermission> findByProjectIdAndSiteUserId(Long projectId, Long siteUserId);
    List<ProjectPermission> findBySiteUserId(Long siteUserId);
    void deleteBySiteUserId(Long siteUserId);
    void deleteByUser(AdminUser user);
    void deleteByProject(Project project);
    void deleteByProjectId(Long projectId);

    /** 统计某 ADMIN 在 [start,end) 区间内归属（有权限）的项目数 */
    @Query("select count(pp) from ProjectPermission pp where pp.user = :user and pp.project.createdAt >= :start and pp.project.createdAt < :end")
    long countByUserAndProjectCreatedAtBetween(@Param("user") AdminUser user, @Param("start") Instant start, @Param("end") Instant end);
}
