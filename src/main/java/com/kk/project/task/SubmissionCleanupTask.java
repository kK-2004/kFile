package com.kk.project.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.oss.OssService;
import com.kk.project.entity.Submission;
import com.kk.project.repo.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionCleanupTask {

    private final SubmissionRepository submissionRepository;
    private final OssService ossService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 每30分钟扫描一次，批量处理最多100条无效记录，异步执行
    @Async
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 30 * 1000L)
    public void cleanupInvalidSubmissions() {
        try {
            List<Submission> batch = submissionRepository.findTop100ByValidFalseOrderByCreatedAtAsc();
            if (batch == null || batch.isEmpty()) return;
            log.info("SubmissionCleanupTask: found {} invalid submissions to clean", batch.size());
            for (Submission s : batch) {
                try {
                    List<String> urls = objectMapper.readValue(s.getFileUrls(), new TypeReference<List<String>>(){});
                    ossService.deleteByUrls(urls);
                } catch (Exception e) {
                    log.warn("Cleanup failed for submission id={}, reason={}", s.getId(), e.getMessage());
                }
                s.setExpired(true);
                submissionRepository.save(s);
            }
        } catch (Exception e) {
            log.warn("SubmissionCleanupTask run error: {}", e.getMessage());
        }
    }
}

