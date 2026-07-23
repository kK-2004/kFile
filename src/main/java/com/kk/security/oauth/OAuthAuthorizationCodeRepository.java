package com.kk.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OAuthAuthorizationCodeRepository
        extends JpaRepository<OAuthAuthorizationCode, Long> {

    Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash);

    /** 定时清理：过期 code。 */
    @Query("select c from OAuthAuthorizationCode c where c.expiresAt <= :now")
    List<OAuthAuthorizationCode> findExpired(Instant now);

    /** 标记 code 已消费（单次消费）。 */
    @Modifying
    @Query(
            "update OAuthAuthorizationCode c set c.consumed = true, c.consumedAt = :when "
                    + "where c.id = :id and c.consumed = false")
    int consume(Long id, Instant when);
}
