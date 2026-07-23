package com.kk.security.oauth;

import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MCP OAuth 授权管理 API（任务 8.1 / 8.2）。
 *
 * <ul>
 *   <li>{@code GET /api/oauth/grants} — 当前用户的 grant 列表（SUPER 看全部）。展示 client 名称、scope、
 *       创建/最近使用时间，<b>不展示任何 token</b>。</li>
 *   <li>{@code DELETE /api/oauth/grants/{id}} — 撤销 grant。用户撤销自己的；SUPER 撤销任意。
 *       撤销后该 grant 下的 access/refresh token 立即失效，agent 下一次请求收到 401。</li>
 * </ul>
 *
 * <p>需 session 认证（挂在 Web session 链）。
 */
@Slf4j
@RestController
@RequestMapping("/api/oauth")
@RequiredArgsConstructor
public class OAuthGrantManagementController {

    private final OAuthAuthorizationGrantRepository grantRepo;
    private final OAuthTokenService tokenService;
    private final AdminUserRepository userRepo;

    @GetMapping("/grants")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> listGrants() {
        AdminUser current = currentUser();
        boolean isSuper = isSuper(current);
        List<OAuthAuthorizationGrant> grants =
                isSuper ? grantRepo.findAll() : grantRepo.findByUser(current);
        // 同一用户对同一 client 可能有多条测试遗留的 grant，按 (user, client) 聚合取最近一条，
        // 避免列表被重复记录刷屏；下线操作会撤销该用户该 client 的所有 grant。
        return grants.stream()
                .filter(g -> !g.isRevoked())
                .collect(java.util.stream.Collectors.toMap(
                        g -> (g.getUser() == null ? "?" : g.getUser().getId()) + "::" + g.getClientId(),
                        g -> g,
                        (a, b) -> (a.getLastUsedAt() == null
                                        || (b.getLastUsedAt() != null
                                                && b.getLastUsedAt().isAfter(a.getLastUsedAt())))
                                ? b
                                : a,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .map(g -> toView(g, isSuper))
                .toList();
    }

    @DeleteMapping("/grants/{id}")
    @org.springframework.transaction.annotation.Transactional
    public void revokeGrant(@PathVariable Long id) {
        AdminUser current = currentUser();
        OAuthAuthorizationGrant grant =
                grantRepo
                        .findById(id)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "授权不存在"));
        boolean isSuper = isSuper(current);
        if (!isSuper && !grant.getUser().getId().equals(current.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权撤销该授权");
        }
        // 下线该用户该 client 的所有未撤销 grant（测试可能遗留多条），一次性全部失效。
        AdminUser owner = grant.getUser();
        String clientId = grant.getClientId();
        for (OAuthAuthorizationGrant g : grantRepo.findByUser(owner)) {
            if (!g.isRevoked() && g.getClientId().equals(clientId)) {
                tokenService.revokeGrant(g, isSuper ? "admin_revoked" : "user_revoked");
            }
        }
    }

    /** 视图：展示 client（含 Agent 应用名）、scope、resource、回调、创建/最近使用，绝不包含 token。 */
    private Map<String, Object> toView(OAuthAuthorizationGrant g, boolean isSuper) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("clientId", g.getClientId());
        // Agent 应用名 + 回调地址（来自关联的 client registration）
        OAuthClientRegistration reg = g.getRegistration();
        if (reg != null) {
            m.put("clientName", reg.getClientName());
            m.put("redirectUris", new java.util.ArrayList<>(reg.redirectUriSet()));
        } else {
            m.put("clientName", null);
            m.put("redirectUris", java.util.Collections.emptyList());
        }
        m.put("scope", g.getScope());
        m.put("resource", g.getResourceUri());
        m.put("createdAt", g.getCreatedAt() == null ? null : g.getCreatedAt().toEpochMilli());
        m.put("lastUsedAt", g.getLastUsedAt() == null ? null : g.getLastUsedAt().toEpochMilli());
        if (isSuper && g.getUser() != null) {
            m.put("username", g.getUser().getUsername());
        }
        return m;
    }

    private AdminUser currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userRepo
                .findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
    }

    private boolean isSuper(AdminUser user) {
        return user != null && "SUPER".equalsIgnoreCase(user.getRole());
    }
}
