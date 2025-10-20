package com.kk.project.repo;

import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    long countByProjectAndSubmitterFingerprint(Project project, String submitterFingerprint);
    List<Submission> findByProject(Project project);
    Page<Submission> findByProject(Project project, Pageable pageable);
    Page<Submission> findByProjectAndValidTrue(Project project, Pageable pageable);
    @Query("select s from Submission s where s.project = ?1 and (s.valid = true or s.valid is null)")
    Page<Submission> findVisibleByProject(Project project, Pageable pageable);
    List<Submission> findByProjectAndSubmitterFingerprintOrderByCreatedAtAsc(Project project, String submitterFingerprint);
    List<Submission> findByProjectAndSubmitterFingerprintOrderByCreatedAtDesc(Project project, String submitterFingerprint);
    List<Submission> findTop100ByValidFalseOrderByCreatedAtAsc();
    void deleteByProject(Project project);

    @Query("select count(distinct s.submitterFingerprint) from Submission s where s.project = ?1")
    long countDistinctSubmitters(Project project);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Submission s set s.valid = true where s.valid is null")
    int backfillValidTrue();

    @Query("select s from Submission s where s.project = ?1 and (s.valid = true or s.valid is null) order by s.createdAt desc")
    List<Submission> findVisibleByProjectOrderByCreatedAtDesc(Project project);
}
