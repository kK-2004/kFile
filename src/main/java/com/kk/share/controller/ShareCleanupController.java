package com.kk.share.controller;

import com.kk.share.ShareCleanupTask;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 XXL-JOB 的 HTTP/curl 任务触发分享链接清理。
 * 调度中心使用 Cron {@code 0 30 1 * * ?}，即每天 01:30 执行。
 */
@RestController
@RequestMapping("/api/internal/jobs")
@RequiredArgsConstructor
public class ShareCleanupController {

    private final ShareCleanupTask cleanupTask;

    @PostMapping("/share-cleanup")
    public ShareCleanupTask.CleanupResult cleanup() {
        return cleanupTask.cleanup();
    }
}
