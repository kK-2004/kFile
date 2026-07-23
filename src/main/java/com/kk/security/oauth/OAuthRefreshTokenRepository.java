package com.kk.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OAuthRefreshTokenRepository extends JpaRepository<OAuthRefreshToken, Long> {

    Optional<OAuthRefreshToken> findByTokenHash(String tokenHash);

    /** 查某 grant 下活动的 refresh token（未消费未吊销）。 */
    @Query(
            "select r from OAuthRefreshToken r where r.grant.id = :grantId "
                    + "and r.consumed = false and r.revoked = false")
    List<OAuthRefreshToken> findActiveByGrant(Long grantId);

    /** 吊销某 grant 下的全部 refresh token（整族吊销）。 */
    @Modifying
    @Query(
            "update OAuthRefreshToken r set r.revoked = true, r.revokedAt = :when "
                    + "where r.grant.id = :grantId and r.revoked = false")
    int revokeByGrant(Long grantId, Instant when);

    /** 标记 refresh token 已轮换消费。 */
    @Modifying
    @Query(
            "update OAuthRefreshToken r set r.consumed = true, r.consumedAt = :when "
                    + "where r.id = :id and r.consumed = false")
    int consume(Long id, Instant when);

    /** 定时清理：过期或已吊销的 refresh token 元数据。 */
    @Query(
            "select r from OAuthRefreshToken r where r.expiresAt <= :now or r.revoked = true")
    List<OAuthRefreshToken> findExpiredOrRevoked(Instant now);
}
