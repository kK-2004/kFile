package com.kk.security.oauth;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 元数据定时清理（任务 2.5）。
 *
 * <p>清理对象：
 * <ul>
 *   <li>未使用且已过期的动态注册 client（预注册 client 不清理）</li>
 *   <li>过期或已消费的 authorization code</li>
 *   <li>过期或已吊销的 access/refresh token 元数据</li>
 * </ul>
 *
 * <p><b>不删除</b>仍需审计的活动 grant：grant 与其归属的 client 记录保留，撤销的 grant 仅置 revoked
 * 标志。这样审计可回溯“谁授权了什么、何时撤销”，而敏感的 token/code 哈希则按期清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthCleanupTask {

    private final OAuthClientRegistrationRepository clientRepo;
    private final OAuthAuthorizationCodeRepository codeRepo;
    private final OAuthAccessTokenRepository accessRepo;
    private final OAuthRefreshTokenRepository refreshRepo;

    @Transactional
    @Scheduled(cron = "0 20 0 * * ?")
    public void clean() {
        Instant now = Instant.now();
        // 1. 过期未使用的动态注册 client：置 disabled（不物理删除，保留审计；disabled 后授权请求即拒绝）。
        int clientCount = 0;
        for (OAuthClientRegistration c : clientRepo.findExpiredDynamic(now)) {
            // 仅清理“从未使用过”的 client（lastUsedAt 为空）。
            if (c.getLastUsedAt() == null) {
                c.setDisabled(true);
                clientRepo.save(c);
                clientCount++;
            }
        }
        // 2. 过期/已消费的 authorization code：物理删除（短期凭据，无审计价值）。
        int codeCount = 0;
        for (OAuthAuthorizationCode c : codeRepo.findExpired(now)) {
            codeRepo.delete(c);
            codeCount++;
        }
        // 3. 过期/已吊销的 access token 元数据：物理删除。
        int accessCount = 0;
        for (OAuthAccessToken t : accessRepo.findExpiredOrRevoked(now)) {
            accessRepo.delete(t);
            accessCount++;
        }
        // 4. 过期/已吊销/已消费的 refresh token 元数据：物理删除。
        int refreshCount = 0;
        for (OAuthRefreshToken r : refreshRepo.findExpiredOrRevoked(now)) {
            refreshRepo.delete(r);
            refreshCount++;
        }
        if (clientCount + codeCount + accessCount + refreshCount > 0) {
            log.info(
                    "BIZ action=OAUTH_CLEANUP clients={} codes={} access={} refresh={}",
                    clientCount, codeCount, accessCount, refreshCount);
        }
    }
}
