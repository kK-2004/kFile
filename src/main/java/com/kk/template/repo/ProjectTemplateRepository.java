package com.kk.template.repo;

import com.kk.template.entity.ProjectTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectTemplateRepository extends JpaRepository<ProjectTemplate, Long> {
    List<ProjectTemplate> findByOwnerId(Long ownerId);
}
