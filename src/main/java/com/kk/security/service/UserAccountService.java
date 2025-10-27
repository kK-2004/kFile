package com.kk.security.service;

import com.kk.security.entity.UserAccount;
import com.kk.security.repo.UserAccountRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserAccountService {
    private final UserAccountRepository repo;

    @Transactional
    public UserAccount findOrCreateByJwt(Jwt jwt, @Nullable JwtAuthenticationToken jwtAuth) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) throw new IllegalArgumentException("JWT 缺少 subject");
        Long externalId;
        try { externalId = Long.parseLong(sub); } catch (Exception e) { throw new IllegalArgumentException("JWT subject 需为数字", e); }
        return repo.findByExternalId(externalId).orElseGet(() -> {
            UserAccount u = new UserAccount();
            u.setExternalId(externalId);
            // 尽力获取用户名/邮箱
            String username = firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("name"), jwt.getClaimAsString("email"));
            u.setUsername(username);
            u.setEmail(jwt.getClaimAsString("email"));
            // 如果site_role为ADMIN 则转为KF_ADMIN
            u.setRole(jwtAuth != null && jwtAuth.getAuthorities().stream().anyMatch(ga -> "ROLE_SITE_ADMIN".equals(ga.getAuthority())) ? "KF_SUPER" : "KF_USER");
            return repo.save(u);
        });
    }

    public UserAccount findByExternalId(Long externalId) {
        return repo.findByExternalId(externalId).orElse(null);
    }

    @Transactional
    public void changeRole(Long externalId, String newRole) {
        UserAccount u = repo.findByExternalId(externalId).orElseThrow();
        if (!List.of("KF_USER", "KF_ADMIN", "KF_SUPER").contains(newRole)) {
            throw new IllegalArgumentException("非法角色: " + newRole);
        }
        if ("KF_SUPER".equals(newRole)) {
            // SUPER 不对外赋予
            throw new IllegalArgumentException("不允许外部赋予 KF_SUPER 角色");
        }
        u.setRole(newRole);
        repo.save(u);
    }

    private String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
