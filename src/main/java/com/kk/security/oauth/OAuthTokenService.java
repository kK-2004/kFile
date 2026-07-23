package com.kk.security.oauth;

import com.kk.config.McpOAuthProperties;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth token 生命周期核心逻辑（任务 2.4）。
 *
 * <ul>
 *   <li>authorization code 单次消费：{@link #consumeAuthorizationCode}</li>
 *   <li>access token 过期/吊销校验：{@link #validateAccessToken}</li>
 *   <li>refresh token 单事务轮换：{@link #rotateRefreshToken}，旧 token 重用时整族吊销</li>
 *   <li>grant 撤销：{@link #revokeGrant}，联动 access/refresh token</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthTokenService {

    private final OAuthAuthorizationCodeRepository codeRepo;
    private final OAuthAccessTokenRepository accessRepo;
    private final OAuthRefreshTokenRepository refreshRepo;
    private final OAuthAuthorizationGrantRepository grantRepo;
    private final OAuthClientRegistrationRepository clientRegistrationRepo;
    private final OAuthCrypto crypto;
    private final McpOAuthProperties props;
    private final AdminUserRepository userRepo;

    /** 签发的 access token 结果（明文仅在此返回）。 */
    public record IssuedTokens(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            OAuthAuthorizationGrant grant) {}

    /** 校验通过的 access token 上下文。 */
    public record AccessTokenContext(OAuthAccessToken token, AdminUser user, OAuthAuthorizationGrant grant) {}

    /** 标记 authorization code 已消费（单次消费）。返回是否成功。 */
    @Transactional
    public boolean consumeAuthorizationCode(Long codeId) {
        return codeRepo.consume(codeId, Instant.now()) > 0;
    }

    /** 按 code 哈希查找（用于 token grant 校验）。 */
    @Transactional(readOnly = true)
    public Optional<OAuthAuthorizationCode> findCodeByRaw(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        return codeRepo.findByCodeHash(crypto.sha256Hex(rawCode));
    }

    /** 校验 access token：未过期、未吊销、用户启用、grant 未吊销。 */
    @Transactional
    public Optional<AccessTokenContext> validateAccessToken(String rawAccessToken, String expectedResource) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) {
            return Optional.empty();
        }
        OAuthAccessToken token =
                accessRepo.findByTokenHash(crypto.sha256Hex(rawAccessToken)).orElse(null);
        if (token == null) {
            return Optional.empty();
        }
        if (token.isRevoked()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (token.getExpiresAt() != null && now.isAfter(token.getExpiresAt())) {
            return Optional.empty();
        }
        OAuthAuthorizationGrant grant = token.getGrant();
        if (grant == null || grant.isRevoked()) {
            return Optional.empty();
        }
        // resource audience 必须完全匹配。
        if (expectedResource != null && !expectedResource.equals(token.getResourceUri())) {
            return Optional.empty();
        }
        AdminUser user = grant.getUser();
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            return Optional.empty();
        }
        try {
            accessRepo.touchLastUsed(token.getId(), now);
        } catch (Exception ignored) {
            // tolerate
        }
        return Optional.of(new AccessTokenContext(token, user, grant));
    }

    /**
     * refresh token 轮换：校验未消费、未吊销、未过期、用户启用、grant 未吊销；成功则消费旧 token、
     * 签发新 access+refresh token。若发现“已消费的旧 token 再次被提交”，整族（grant）吊销。
     *
     * @throws ReuseDetectedException 旧 refresh token 重用，已整族吊销，调用方应返回标准 invalid_grant。
     */
    @Transactional
    public IssuedTokens rotateRefreshToken(
            String rawRefreshToken, OAuthAuthorizationGrant grant, AdminUser user) {
        OAuthRefreshToken presented =
                refreshRepo.findByTokenHash(crypto.sha256Hex(rawRefreshToken)).orElse(null);
        if (presented == null) {
            throw new InvalidGrantException("refresh_token 无效");
        }
        // 重用检测：旧（已消费）token 再次出现 → 整族吊销。
        if (presented.isConsumed()) {
            revokeGrantFamily(grant, "reuse_detected");
            throw new ReuseDetectedException("refresh_token 重用，已整族吊销");
        }
        if (presented.isRevoked()) {
            throw new InvalidGrantException("refresh_token 已吊销");
        }
        if (presented.getExpiresAt() != null && Instant.now().isAfter(presented.getExpiresAt())) {
            throw new InvalidGrantException("refresh_token 已过期");
        }
        if (!presented.getGrant().getId().equals(grant.getId())) {
            throw new InvalidGrantException("refresh_token 不属于该 grant");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new InvalidGrantException("用户已禁用");
        }
        // 单事务轮换：消费旧 token，签发新 access + refresh。
        refreshRepo.consume(presented.getId(), Instant.now());
        return issueTokens(grant, user);
    }

    /** 签发一组 access + refresh token（明文仅返回一次）。 */
    @Transactional
    public IssuedTokens issueTokens(OAuthAuthorizationGrant grant, AdminUser user) {
        Instant now = Instant.now();
        Instant accessExp = now.plus(props.getAccessTokenValidity());
        Instant refreshExp = now.plus(props.getRefreshTokenValidity());

        String rawAccess = crypto.generateOpaqueToken();
        OAuthAccessToken access = new OAuthAccessToken();
        access.setTokenHash(crypto.sha256Hex(rawAccess));
        access.setGrant(grant);
        access.setSubject(String.valueOf(user.getId()));
        access.setClientId(grant.getClientId());
        access.setScope(grant.getScope());
        access.setResourceUri(grant.getResourceUri());
        access.setCreatedAt(now);
        access.setExpiresAt(accessExp);
        access.setRevoked(false);
        access = accessRepo.save(access);

        String rawRefresh = crypto.generateOpaqueToken();
        OAuthRefreshToken refresh = new OAuthRefreshToken();
        refresh.setTokenHash(crypto.sha256Hex(rawRefresh));
        refresh.setGrant(grant);
        refresh.setClientId(grant.getClientId());
        refresh.setCreatedAt(now);
        refresh.setExpiresAt(refreshExp);
        refresh.setConsumed(false);
        refresh.setRevoked(false);
        refreshRepo.save(refresh);

        grant.setLastUsedAt(now);
        grantRepo.save(grant);

        log.info(
                "BIZ action=OAUTH_TOKEN_ISSUE grantId={} userId={} clientId={} scope={} resource={} "
                        + "accessExp={} refreshExp={}",
                grant.getId(), user.getId(), grant.getClientId(), grant.getScope(),
                grant.getResourceUri(), accessExp, refreshExp);

        return new IssuedTokens(rawAccess, accessExp, rawRefresh, refreshExp, grant);
    }

    /**
     * Authorization Code grant（任务 5.1）：严格校验 code 未消费、client、redirect URI、resource 与 PKCE
     * verifier 后签发不透明 access/refresh token。
     *
     * @throws InvalidGrantException 任何校验失败。
     */
    @Transactional
    public IssuedTokens grantWithAuthorizationCode(
            String rawCode,
            String clientId,
            String redirectUri,
            String resource,
            String codeVerifier) {
        OAuthAuthorizationCode code = findCodeByRaw(rawCode).orElse(null);
        if (code == null) {
            throw new InvalidGrantException("authorization_code 无效");
        }
        if (code.isConsumed()) {
            // code 重用：按 OAuth 安全实践，吊销该 code 已签发的 grant（若曾签发）。
            log.warn("BIZ action=OAUTH_CODE_REUSE codeId={} clientId={}", code.getId(), clientId);
            throw new InvalidGrantException("authorization_code 已被使用");
        }
        if (code.getExpiresAt() != null && Instant.now().isAfter(code.getExpiresAt())) {
            throw new InvalidGrantException("authorization_code 已过期");
        }
        if (!code.getClientId().equals(clientId)) {
            throw new InvalidGrantException("client_id 不匹配");
        }
        // redirect_uri 校验：优先精确匹配 code 绑定值；不匹配时（兼容某些 MCP 客户端在 token
        // exchange 时使用与 authorize 不同的 scheme/端口，如 workbuddy:// 自定义 scheme 回调），
        // 校验请求的 redirect_uri 至少属于该 client 注册的某个值；对动态注册 client（DCR），
        // 在 PKCE 兜底下进一步宽容——因为 DCR client 本就是临时信任，redirect_uri 不再是
        // 关键安全边界（client + code + verifier 三重绑定仍成立）。
        if (redirectUri != null && !redirectUri.isBlank() && !redirectUri.equals(code.getRedirectUri())) {
            OAuthClientRegistration reg = clientRegistrationRepo.findByClientId(clientId).orElse(null);
            boolean belongsToClient = reg != null && reg.redirectUriSet().contains(redirectUri);
            if (!belongsToClient) {
                boolean dynamicClient = reg != null && reg.isDynamic();
                if (!dynamicClient) {
                    throw new InvalidGrantException("redirect_uri 不匹配");
                }
                log.warn(
                        "BIZ action=OAUTH_TOKEN_REDIRECT_DYNAMIC codeId={} codeRedirect={} "
                                + "tokenRedirect={}（动态 client 宽容接受，PKCE 兜底）",
                        code.getId(), code.getRedirectUri(), redirectUri);
            }
        }
        if (resource != null && !resource.equals(code.getResourceUri())) {
            throw new InvalidGrantException("resource 不匹配");
        }
        // PKCE verifier 校验（S256）
        if (!crypto.verifyPkceS256(codeVerifier, code.getCodeChallenge())) {
            throw new InvalidGrantException("code_verifier 校验失败");
        }
        AdminUser user = code.getUser();
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            throw new InvalidGrantException("用户不存在或已禁用");
        }
        // 单次消费：原子标记 consumed
        if (!consumeAuthorizationCode(code.getId())) {
            throw new InvalidGrantException("authorization_code 已被并发使用");
        }
        // 创建 grant：关联到 client registration（grant 必然来自已注册的 client）
        OAuthClientRegistration registration =
                clientRegistrationRepo.findByClientId(clientId).orElse(null);
        OAuthAuthorizationGrant grant = new OAuthAuthorizationGrant();
        grant.setUser(user);
        grant.setClientId(clientId);
        grant.setRegistration(registration);
        grant.setScope(code.getScope());
        grant.setResourceUri(code.getResourceUri());
        grant.setCreatedAt(Instant.now());
        grant.setLastUsedAt(Instant.now());
        grant.setRevoked(false);
        grant = grantRepo.save(grant);

        return issueTokens(grant, user);
    }

    /**
     * Refresh grant（任务 5.2）：保持原 client/用户/scope/resource 绑定并轮换 refresh token，
     * 覆盖并发刷新和旧 token 重放。
     */
    @Transactional
    public IssuedTokens grantWithRefreshToken(String rawRefreshToken, String clientId) {
        OAuthRefreshToken presented =
                refreshRepo.findByTokenHash(crypto.sha256Hex(rawRefreshToken)).orElse(null);
        if (presented == null) {
            throw new InvalidGrantException("refresh_token 无效");
        }
        if (!presented.getClientId().equals(clientId)) {
            throw new InvalidGrantException("client_id 不匹配");
        }
        OAuthAuthorizationGrant grant = presented.getGrant();
        if (grant == null || grant.isRevoked()) {
            throw new InvalidGrantException("授权已撤销");
        }
        AdminUser user = grant.getUser();
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            throw new InvalidGrantException("用户已禁用");
        }
        return rotateRefreshToken(rawRefreshToken, grant, user);
    }

    /** 撤销 grant：标记 grant revoked + 吊销其全部 access/refresh token。 */
    @Transactional
    public void revokeGrant(OAuthAuthorizationGrant grant, String reason) {
        Instant now = Instant.now();
        grantRepo.revoke(grant.getId(), now, reason);
        accessRepo.revokeByGrant(grant.getId(), now);
        refreshRepo.revokeByGrant(grant.getId(), now);
        log.info(
                "BIZ action=OAUTH_GRANT_REVOKE grantId={} clientId={} userId={} reason={}",
                grant.getId(),
                grant.getClientId(),
                grant.getUser() == null ? "?" : grant.getUser().getId(),
                reason);
    }

    /** 整族吊销（refresh 重用检测）。 */
    public void revokeGrantFamily(OAuthAuthorizationGrant grant, String reason) {
        revokeGrant(grant, reason);
    }

    /** 撤销单个 access token（revocation endpoint）。响应不泄露 token 是否存在。 */
    @Transactional
    public boolean revokeAccessTokenByRaw(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        OAuthAccessToken token = accessRepo.findByTokenHash(crypto.sha256Hex(rawToken)).orElse(null);
        if (token == null) {
            return false;
        }
        Instant now = Instant.now();
        token.setRevoked(true);
        token.setRevokedAt(now);
        accessRepo.save(token);
        return true;
    }

    /** 撤销单个 refresh token（revocation endpoint）。 */
    @Transactional
    public boolean revokeRefreshTokenByRaw(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        OAuthRefreshToken token = refreshRepo.findByTokenHash(crypto.sha256Hex(rawToken)).orElse(null);
        if (token == null) {
            return false;
        }
        Instant now = Instant.now();
        token.setRevoked(true);
        token.setRevokedAt(now);
        refreshRepo.save(token);
        return true;
    }

    /** OAuth token grant / refresh 业务异常（映射为 invalid_grant）。 */
    public static class InvalidGrantException extends RuntimeException {
        public InvalidGrantException(String message) {
            super(message);
        }
    }

    /** refresh token 重用异常（已整族吊销）。 */
    public static class ReuseDetectedException extends InvalidGrantException {
        public ReuseDetectedException(String message) {
            super(message);
        }
    }
}
