package com.kk.security.gateway;

import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 网关身份 → kfile 账号。先按 guid 查；未绑定时按网关用户名找同名且未绑定的老账号并回写 guid。
 * 不自动开户：kfile 账号须由超级管理员预先创建（按网关用户名）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayUserResolver {

    private final AdminUserRepository userRepository;

    public sealed interface Result permits Bound, NotProvisioned, Disabled, Conflict {
    }

    public record Bound(AdminUser user) implements Result {
    }

    /** kfile 中没有该网关用户对应的账号。 */
    public record NotProvisioned() implements Result {
    }

    public record Disabled() implements Result {
    }

    /** 同名账号已绑定其他网关用户（网关侧改名/重名等），需管理员处理。 */
    public record Conflict() implements Result {
    }

    @Transactional
    public Result resolve(GatewayTokenVerifier.GatewayIdentity identity) {
        long guid;
        try {
            guid = Long.parseLong(identity.guid());
        } catch (NumberFormatException e) {
            return new NotProvisioned();
        }
        AdminUser user = userRepository.findByGuid(guid).orElse(null);
        if (user == null) {
            if (identity.username() == null || identity.username().isBlank()) {
                return new NotProvisioned();
            }
            user = userRepository.findByUsername(identity.username()).orElse(null);
            if (user == null) {
                return new NotProvisioned();
            }
            if (user.getGuid() != null) {
                return new Conflict();
            }
            user.setGuid(guid);
            userRepository.save(user);
            log.info("kfile 账号 {} 绑定网关用户 guid={}", user.getUsername(), guid);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            return new Disabled();
        }
        return new Bound(user);
    }
}
