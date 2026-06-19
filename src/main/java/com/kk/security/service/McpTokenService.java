package com.kk.security.service;

import com.kk.common.service.AppConfigService;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.McpAccessToken;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.McpAccessTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * MCP 长期令牌的签发、鉴权、吊销。
 * - 签发：
 *   - {@link #issue} 账号密码校验通过后签发（保留作管理页应急/兼容入口）。
 *   - {@link #issueForCurrentUser} 基于当前 session 登录用户签发（OAuth 网页授权流程主入口，不重输密码）。
 * - 鉴权：对请求 token 算哈希查库，未过期未吊销则返回绑定 AdminUser 与 authorities。
 * - 与现有 session 认证并存，互不影响。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpTokenService {

    private final McpAccessTokenRepository tokenRepo;
    private final AdminUserRepository userRepo;
    private final AuthenticationManager authenticationManager;
    private final AppConfigService appConfigService;
    private final SecureRandom random = new SecureRandom();

    /** token 有效期，默认 6 个月（可通过配置覆盖） */
    @Value("${app.mcp.token-validity:P182D}")
    private Duration tokenValidity;

    /** 签发结果：明文 token 仅此处返回一次 */
    public record IssuedToken(String accessToken, Long expiresAt, Long tokenId) {}

    /** 鉴权结果：绑定用户 + authorities（ROLE_SUPER / ROLE_ADMIN） */
    public record AuthResult(AdminUser user, List<GrantedAuthority> authorities) {}

    @Transactional
    public IssuedToken issue(String username, String password) {
        // 复用现有 AuthenticationManager 校验账号密码（与 session 登录同一套）
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        AdminUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        return doIssue(user, username);
    }

    /**
     * 基于当前 session 登录用户签发 token（OAuth 网页授权流程主入口）。
     * 不再校验密码——用户已通过 session 认证。
     */
    @Transactional
    public IssuedToken issueForCurrentUser(AdminUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return doIssue(user, user.getUsername());
    }

    /** 实际生成并落库 token 的共用逻辑 */
    private IssuedToken doIssue(AdminUser user, String usernameForLog) {
        // 生成 ≥32 字节随机 token，URL 安全编码
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenValidity);

        McpAccessToken entity = new McpAccessToken();
        entity.setTokenHash(sha256Hex(rawToken));
        entity.setUser(user);
        entity.setExpiresAt(expiresAt);
        entity.setRevoked(false);
        entity.setLastUsedAt(now);
        entity = tokenRepo.save(entity);

        log.info("BIZ action=MCP_TOKEN_ISSUE tokenId={} userId={} username={} expiresAt={}",
                entity.getId(), user.getId(), usernameForLog, expiresAt);
        return new IssuedToken(rawToken, expiresAt.toEpochMilli(), entity.getId());
    }

    /**
     * 校验 OAuth 授权回调的 redirect_uri 是否在配置的白名单前缀内。
     * 空 allows 列表 = 拒绝全部（最安全默认）。
     * 不合法抛 400。
     */
    public void validateRedirectUri(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri 不能为空");
        }
        String trimmed = redirectUri.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri 必须是 http(s) URL");
        }
        List<String> allows = appConfigService.getStringList(AppConfigService.KEY_MCP_REDIRECT_ALLOWED_PREFIXES);
        if (allows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未配置 MCP 授权回调白名单（MCP_REDIRECT_ALLOWED_PREFIXES），禁止任意回调");
        }
        for (String prefix : allows) {
            if (prefix != null && trimmed.startsWith(prefix.trim())) {
                return; // 命中白名单
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "redirect_uri 不在允许的前缀白名单内: " + redirectUri);
    }

    /**
     * 鉴权：返回绑定用户与 authorities；未命中/过期/吊销返回 null（交由调用方不设置认证 → 401）。
     */
    @Transactional
    public AuthResult authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        McpAccessToken token = tokenRepo.findByTokenHash(sha256Hex(rawToken)).orElse(null);
        if (token == null) return null;
        if (Boolean.TRUE.equals(token.getRevoked())) return null;
        if (token.getExpiresAt() != null && Instant.now().isAfter(token.getExpiresAt())) return null;

        AdminUser user = token.getUser();
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) return null;

        // 更新最近使用时间（容忍失败）
        try {
            token.setLastUsedAt(Instant.now());
            tokenRepo.save(token);
        } catch (Exception ignored) {}

        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();
        return new AuthResult(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Transactional
    public void revoke(Long tokenId, AdminUser currentUser) {
        McpAccessToken token = tokenRepo.findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "令牌不存在"));
        boolean isSuper = currentUser != null && "SUPER".equalsIgnoreCase(currentUser.getRole());
        if (!isSuper && (currentUser == null || !currentUser.getId().equals(token.getUser().getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权吊销该令牌");
        }
        token.setRevoked(true);
        token.setRevokedAt(Instant.now());
        tokenRepo.save(token);
        log.info("BIZ action=MCP_TOKEN_REVOKE tokenId={} operator={}", tokenId,
                currentUser == null ? "?" : currentUser.getUsername());
    }

    /**
     * 管理端列表：SUPER 看全部未吊销的，否则只看自己的未吊销的。
     * 已吊销的令牌逻辑删除——不再显示（定时任务每天 0:10 物理清理）。
     */
    public List<McpAccessToken> listForUser(AdminUser currentUser) {
        boolean isSuper = currentUser != null && "SUPER".equalsIgnoreCase(currentUser.getRole());
        return isSuper ? tokenRepo.findAllActive() : tokenRepo.findActiveByUser(currentUser);
    }

    public Map<String, Object> toView(McpAccessToken t) {
        return Map.of(
                "id", t.getId(),
                "username", t.getUser().getUsername(),
                "createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toEpochMilli(),
                "expiresAt", t.getExpiresAt() == null ? null : t.getExpiresAt().toEpochMilli(),
                "lastUsedAt", t.getLastUsedAt() == null ? null : t.getLastUsedAt().toEpochMilli(),
                "revoked", Boolean.TRUE.equals(t.getRevoked())
        );
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
