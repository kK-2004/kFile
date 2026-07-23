package com.kk.security.oauth;

import com.kk.security.entity.AdminUser;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface OAuthAuthorizationGrantRepository
        extends JpaRepository<OAuthAuthorizationGrant, Long> {

    /** 用户的全部 grant（管理端列表）。 */
    List<OAuthAuthorizationGrant> findByUser(AdminUser user);

    /** 全部 grant（SUPER 视图）。 */
    List<OAuthAuthorizationGrant> findAll();

    /** 吊销某 grant（标记 revoked）。 */
    @Modifying
    @Query(
            "update OAuthAuthorizationGrant g set g.revoked = true, g.revokedAt = :when, "
                    + "g.revocationReason = :reason where g.id = :id and g.revoked = false")
    int revoke(Long id, Instant when, String reason);
}
