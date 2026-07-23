package com.kk.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OAuthAccessTokenRepository extends JpaRepository<OAuthAccessToken, Long> {

    Optional<OAuthAccessToken> findByTokenHash(String tokenHash);

    /** 定时清理：过期或已吊销的 access token 元数据。 */
    @Query(
            "select t from OAuthAccessToken t where t.expiresAt <= :now or t.revoked = true")
    List<OAuthAccessToken> findExpiredOrRevoked(Instant now);

    /** 吊销某 grant 下的全部 access token。 */
    @Modifying
    @Query(
            "update OAuthAccessToken t set t.revoked = true, t.revokedAt = :when "
                    + "where t.grant.id = :grantId and t.revoked = false")
    int revokeByGrant(Long grantId, Instant when);

    @Modifying
    @Query("update OAuthAccessToken t set t.lastUsedAt = :now where t.id = :id")
    int touchLastUsed(Long id, Instant now);
}
