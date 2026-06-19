package com.kk.security.repo;

import com.kk.security.entity.AdminUser;
import com.kk.security.entity.McpAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface McpAccessTokenRepository extends JpaRepository<McpAccessToken, Long> {
    Optional<McpAccessToken> findByTokenHash(String tokenHash);
    List<McpAccessToken> findByUser(AdminUser user);

    /** 仅未吊销的令牌（管理端列表用，已吊销的逻辑删除不显示）。 */
    default List<McpAccessToken> findActiveByUser(AdminUser user) {
        return findByUser(user).stream()
                .filter(t -> !Boolean.TRUE.equals(t.getRevoked()))
                .toList();
    }

    /** SUPER 视图：仅未吊销的全部令牌。 */
    @Query("select t from McpAccessToken t where t.revoked <> true or t.revoked is null")
    List<McpAccessToken> findAllActive();

    /** 定时任务：物理删除已吊销（逻辑删除）的令牌。返回删除条数。 */
    @Modifying
    @Query("delete from McpAccessToken t where t.revoked = true and (t.revokedAt is null or t.revokedAt <= :cutoff)")
    int deleteRevokedBefore(Instant cutoff);
}
