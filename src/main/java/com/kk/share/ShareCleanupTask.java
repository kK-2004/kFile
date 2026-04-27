package com.kk.share;

import com.kk.share.repo.ShareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShareCleanupTask {

    private final ShareLinkRepository shareLinkRepository;

    @Transactional
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpired() {
        int count = shareLinkRepository.deleteExpiredBefore(Instant.now());
        if (count > 0) {
            log.info("清理过期分享链接 {} 条", count);
        }
    }
}
