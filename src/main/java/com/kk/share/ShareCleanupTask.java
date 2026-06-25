package com.kk.share;

import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShareCleanupTask {

    private final ShareLinkRepository shareLinkRepository;
    private final ShareLinkItemRepository shareLinkItemRepository;

    @Transactional
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpired() {
        // 先取出过期链接 id，删除其下的分享条目，再删除链接本身（避免外键约束失败）
        List<ShareLink> expired = shareLinkRepository.findByExpireAtBefore(Instant.now());
        if (expired.isEmpty()) return;
        List<Long> ids = expired.stream().map(ShareLink::getId).toList();
        shareLinkItemRepository.deleteByShareLinkIdIn(ids);
        shareLinkRepository.deleteAll(expired);
        if (!ids.isEmpty()) {
            log.info("清理过期分享链接 {} 条", ids.size());
        }
    }
}
