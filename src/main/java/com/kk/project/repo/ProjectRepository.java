package com.kk.project.repo;

import com.kk.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    int countByCreatorSiteUserIdAndCreatedAtBetween(Long creatorSiteUserId, Instant start, Instant end);
}
