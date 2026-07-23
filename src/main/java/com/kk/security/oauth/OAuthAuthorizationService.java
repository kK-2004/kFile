package com.kk.security.oauth;

import com.kk.config.McpOAuthProperties;
import com.kk.security.entity.AdminUser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * OAuth 授权请求校验与授权码签发（任务 4.3 / 4.5 / 4.6）。
 *
 * <ul>
 *   <li>4.3 校验 client id、scope、resource、state、精确 redirect URI、code challenge 与 S256 方法。
 *       任何校验失败均不向未验证 URI 重定向。</li>
 *   <li>4.5/4.6 批准时签发绑定用户/client/redirect URI/resource/PKCE challenge 的短时单次 code，
 *       仅回传 code 与原始 state；拒绝时返回标准 access_denied。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthAuthorizationService {

    private final OAuthClientService clientService;
    private final OAuthAuthorizationGrantRepository grantRepo;
    private final OAuthAuthorizationCodeRepository codeRepo;
    private final OAuthCrypto crypto;
    private final McpOAuthProperties props;

    /** 已校验的授权请求上下文（用于渲染 consent 页）。 */
    public record AuthorizationRequest(
            String clientId,
            String redirectUri,
            String state,
            String scope,
            String resource,
            String codeChallenge,
            String codeChallengeMethod,
            OAuthClientRegistration client) {}

    /** 校验授权请求参数。任何失败抛 400（绝不向未验证 redirect URI 重定向）。 */
    @Transactional(readOnly = true)
    public AuthorizationRequest validateRequest(
            String clientId,
            String redirectUri,
            String responseType,
            String state,
            String scope,
            String resource,
            String codeChallenge,
            String codeChallengeMethod) {
        // 1. client 必须存在且启用
        OAuthClientRegistration client =
                clientService
                        .findByClientId(clientId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST, "未知或已禁用的 client_id"));

        // 2. response_type=code
        if (!"code".equals(responseType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "response_type 仅支持 code");
        }

        // 3. redirect_uri 精确匹配（必须先校验，之后才允许向其重定向）
        clientService.validateRedirectUriExact(client, redirectUri);

        // 4. scope 必须包含配置的 mcp:tools
        if (scope == null || !scope.trim().equals(props.getScope())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "scope 必须为 " + props.getScope());
        }

        // 5. resource 必须与规范化 MCP resource URL 完全一致
        if (resource == null || !resource.trim().equals(props.resourceUrl())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "resource 必须为 " + props.resourceUrl());
        }

        // 6. state 必填
        if (state == null || state.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state 必填");
        }

        // 7. PKCE: code_challenge 必填，方法必须 S256
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "code_challenge 必填");
        }
        if (!"S256".equals(codeChallengeMethod)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "code_challenge_method 必须为 S256");
        }

        return new AuthorizationRequest(
                clientId,
                redirectUri,
                state,
                scope,
                resource,
                codeChallenge,
                codeChallengeMethod,
                client);
    }

    /**
     * 用户批准授权：创建 grant + 签发短时单次 code。仅返回 redirect URL（含 code 与原始 state），
     * 不含任何 token。
     */
    @Transactional
    public String approve(AuthorizationRequest req, AdminUser user) {
        // 创建（或复用）grant
        OAuthAuthorizationGrant grant = new OAuthAuthorizationGrant();
        grant.setUser(user);
        grant.setClientId(req.clientId());
        grant.setRegistration(req.client());
        grant.setScope(req.scope());
        grant.setResourceUri(req.resource());
        grant.setCreatedAt(Instant.now());
        grant.setLastUsedAt(Instant.now());
        grant.setRevoked(false);
        grant = grantRepo.save(grant);

        // 签发 authorization code
        String rawCode = crypto.generateAuthorizationCode();
        OAuthAuthorizationCode code = new OAuthAuthorizationCode();
        code.setCodeHash(crypto.sha256Hex(rawCode));
        code.setUser(user);
        code.setClientId(req.clientId());
        code.setRedirectUri(req.redirectUri());
        code.setResourceUri(req.resource());
        code.setScope(req.scope());
        code.setCodeChallenge(req.codeChallenge());
        code.setCreatedAt(Instant.now());
        code.setExpiresAt(Instant.now().plus(props.getAuthorizationCodeValidity()));
        code.setConsumed(false);
        codeRepo.save(code);

        clientService.touchLastUsed(req.client());

        log.info(
                "BIZ action=OAUTH_APPROVE grantId={} userId={} clientId={} redirectUri={}",
                grant.getId(), user.getId(), req.clientId(), req.redirectUri());

        // 仅回传 code 与原始 state
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", rawCode);
        params.put("state", req.state());
        return req.redirectUri() + (req.redirectUri().contains("?") ? "&" : "?") + toQuery(params);
    }

    /** 用户拒绝：返回标准 access_denied（回已验证 redirect URI，含原始 state）。 */
    public String deny(AuthorizationRequest req) {
        log.info("BIZ action=OAUTH_DENY clientId={} redirectUri={}", req.clientId(), req.redirectUri());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", "access_denied");
        params.put("state", req.state());
        return req.redirectUri() + (req.redirectUri().contains("?") ? "&" : "?") + toQuery(params);
    }

    private static String toQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }
}
