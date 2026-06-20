package com.kk.project.repo;

import com.kk.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** 统计某 owner 本月在 [start,end) 区间创建的项目数（配额用，只算自己创建的） */
    @Query("select count(p) from Project p where p.ownerUserId = :ownerUserId and p.createdAt >= :start and p.createdAt < :end")
    long countByOwnerUserIdAndCreatedAtBetween(@Param("ownerUserId") Long ownerUserId, @Param("start") Instant start, @Param("end") Instant end);
}
