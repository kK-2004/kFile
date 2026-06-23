package com.kk.project.repo;

import com.kk.project.entity.XxlJobRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface XxlJobRefRepository extends JpaRepository<XxlJobRef, Long> {
    Optional<XxlJobRef> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);
}
