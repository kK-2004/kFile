package com.kk.project.repo;

import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    long countByProjectAndSubmitterFingerprint(Project project, String submitterFingerprint);
    List<Submission> findByProject(Project project);
    Page<Submission> findByProject(Project project, Pageable pageable);
    List<Submission> findByProjectAndSubmitterFingerprintOrderByCreatedAtAsc(Project project, String submitterFingerprint);
    void deleteByProject(Project project);

    @Query("select count(distinct s.submitterFingerprint) from Submission s where s.project = ?1")
    long countDistinctSubmitters(Project project);
}
