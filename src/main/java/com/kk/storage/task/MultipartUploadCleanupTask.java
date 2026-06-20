package com.kk.storage.task;

import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileUploadRepository;
import com.kk.storage.service.MultipartUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 回收放弃的分片上传：扫描 {@link StoredFileUpload} status=UPLOADING 且 updatedAt 超过阈值（24h）的记录，
 * 调 {@code abortMultipartUpload} 释放 MinIO 未完成 multipart + 删 DB 记录 + 删 StoredFile(UPLOADING)。
 * 参考 {@link com.kk.share.ShareCleanupTask}。
 * <p>
 * MinIO 未启用时 multipartProvider 为空，直接跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultipartUploadCleanupTask {

    /** 超时阈值：UPLOADING 状态超过 24h 视为放弃 */
    private static final Duration ABANDON_THRESHOLD = Duration.ofHours(24);

    private final StoredFileUploadRepository uploadRepository;
    /** MinIO 分片服务（条件装配，未启用时为空） */
    private final ObjectProvider<MultipartUploadService> multipartProvider;

    @Scheduled(cron = "0 30 2 * * ?") // 每天 02:30（与分享清理 02:00 错开）
    public void cleanupAbandoned() {
        MultipartUploadService svc = multipartProvider.getIfAvailable();
        if (svc == null) {
            return; // MinIO 未启用，无需清理
        }
        Instant before = Instant.now().minus(ABANDON_THRESHOLD);
        List<StoredFileUpload> abandoned = uploadRepository.findByStatusAndUpdatedAtBefore(
                StoredFileUpload.STATUS_UPLOADING, before);
        if (abandoned.isEmpty()) {
            return;
        }
        log.info("清理放弃的分片上传 {} 条（阈值 {}h）", abandoned.size(), ABANDON_THRESHOLD.toHours());
        int ok = 0;
        for (StoredFileUpload u : abandoned) {
            try {
                svc.cleanupRecord(u);
                ok++;
            } catch (Exception e) {
                log.warn("清理分片上传记录失败 id={}, contentMd5={}, msg={}", u.getId(), u.getContentMd5(), e.getMessage());
            }
        }
        log.info("分片上传清理完成：成功 {}/{}", ok, abandoned.size());
    }
}
