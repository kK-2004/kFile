package com.kk.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OAuthClientRegistrationRepository
        extends JpaRepository<OAuthClientRegistration, Long> {

    Optional<OAuthClientRegistration> findByClientId(String clientId);

    /** 按 redirect URIs JSON 去重（相同注册）。 */
    Optional<OAuthClientRegistration> findByRedirectUrisJsonAndDynamicTrue(
            String redirectUrisJson);

    /** 按 client_name 去重（agent 每次用相同名称，但 localhost 动态端口 redirect_uri 会变）。 */
    Optional<OAuthClientRegistration> findByClientNameAndDynamicTrue(String clientName);

    /** 定时清理：过期且未使用的动态注册 client。 */
    @Query(
            "select c from OAuthClientRegistration c where c.dynamic = true "
                    + "and c.disabled = false and c.clientSecretExpiresAt is not null "
                    + "and c.clientSecretExpiresAt <= :now")
    List<OAuthClientRegistration> findExpiredDynamic(Instant now);

    @Modifying
    @Query(
            "update OAuthClientRegistration c set c.lastUsedAt = :now where c.id = :id")
    int touchLastUsed(Long id, Instant now);
}
