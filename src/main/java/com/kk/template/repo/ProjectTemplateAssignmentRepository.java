package com.kk.template.repo;

import com.kk.security.entity.AdminUser;
import com.kk.template.entity.ProjectTemplate;
import com.kk.template.entity.ProjectTemplateAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectTemplateAssignmentRepository extends JpaRepository<ProjectTemplateAssignment, Long> {
    List<ProjectTemplateAssignment> findByUser(AdminUser user);
    List<ProjectTemplateAssignment> findByTemplate(ProjectTemplate template);
    Optional<ProjectTemplateAssignment> findByUserAndTemplate(AdminUser user, ProjectTemplate template);
    void deleteByTemplate(ProjectTemplate template);
    void deleteByUserAndTemplate(AdminUser user, ProjectTemplate template);

    /**
     * 查询某用户可用的全部模板（owner=自己 ∪ 被分配给自己的）。
     */
    @Query("select distinct t from ProjectTemplate t " +
            "where t.ownerId = :userId " +
            "   or t.id in (select a.template.id from ProjectTemplateAssignment a where a.user.id = :userIdUid) " +
            "order by t.id desc")
    List<ProjectTemplate> findUsableByUserId(@Param("userId") Long userId, @Param("userIdUid") Long userIdUid);
}
