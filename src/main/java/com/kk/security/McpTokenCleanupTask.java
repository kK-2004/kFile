package com.kk.security;

import com.kk.security.repo.McpAccessTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * MCP 令牌清理：每天 0:10 物理删除已吊销（逻辑删除）的令牌。
 * 吊销后令牌立即失效（authenticate 返回 null），但记录会保留到此处清理。
 * @EnableScheduling 已在 KFileApplication 启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpTokenCleanupTask {
    private final McpAccessTokenRepository tokenRepo;

    @Transactional
    @Scheduled(cron = "0 10 0 * * ?")
    public void cleanRevoked() {
        int count = tokenRepo.deleteRevokedBefore(Instant.now());
        if (count > 0) {
            log.info("BIZ action=MCP_TOKEN_CLEANUP count={}", count);
        }
    }
}
