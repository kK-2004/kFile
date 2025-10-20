package com.kk.project.task;

import com.kk.project.repo.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionDataBackfillRunner implements ApplicationRunner {

    private final SubmissionRepository submissionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int n = submissionRepository.backfillValidTrue();
            if (n > 0) {
                log.info("Backfilled 'valid=true' for {} submission rows with null valid", n);
            }
        } catch (Exception e) {
            log.warn("Backfill for submissions.valid failed: {}", e.getMessage());
        }
    }
}

