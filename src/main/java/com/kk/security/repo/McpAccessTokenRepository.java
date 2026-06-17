package com.kk.security.repo;

import com.kk.security.entity.AdminUser;
import com.kk.security.entity.McpAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpAccessTokenRepository extends JpaRepository<McpAccessToken, Long> {
    Optional<McpAccessToken> findByTokenHash(String tokenHash);
    List<McpAccessToken> findByUser(AdminUser user);
}
