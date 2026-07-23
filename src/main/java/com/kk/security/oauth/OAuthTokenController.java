package com.kk.security.oauth;

import com.kk.security.oauth.OAuthTokenService.InvalidGrantException;
import com.kk.security.oauth.OAuthTokenService.IssuedTokens;
import com.kk.security.oauth.OAuthTokenService.ReuseDetectedException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth token endpoint 与 revocation endpoint（任务 5.1 / 5.2 / 5.3）。
 *
 * <ul>
 *   <li>{@code POST /oauth2/token} — 支持 authorization_code 与 refresh_token grant。</li>
 *   <li>{@code POST /oauth2/revoke} — 标准 token revocation，响应不泄露 token 是否存在。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class OAuthTokenController {

    private final OAuthTokenService tokenService;

    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "resource", required = false) String resource,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier,
            @RequestParam(value = "refresh_token", required = false) String refreshToken) {
        try {
            IssuedTokens issued;
            if ("authorization_code".equals(grantType)) {
                issued =
                        tokenService.grantWithAuthorizationCode(
                                code, clientId, redirectUri, resource, codeVerifier);
            } else if ("refresh_token".equals(grantType)) {
                issued = tokenService.grantWithRefreshToken(refreshToken, clientId);
            } else {
                return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", issued.accessToken());
            body.put("token_type", "Bearer");
            long expiresIn =
                    (issued.accessTokenExpiresAt().toEpochMilli() - System.currentTimeMillis()) / 1000;
            body.put("expires_in", Math.max(0, expiresIn));
            body.put("refresh_token", issued.refreshToken());
            body.put("scope", issued.grant().getScope());
            // MCP/RFC 8707 resource indicator
            body.put("resource", issued.grant().getResourceUri());
            return ResponseEntity.ok(body);
        } catch (ReuseDetectedException e) {
            // refresh token 重用：整族已吊销，要求重新授权
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "refresh token reuse detected");
        } catch (InvalidGrantException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", e.getMessage());
        }
    }

    /**
     * 标准 token revocation endpoint（任务 5.3）。对任意 token 都返回 200，不泄露 token 是否存在。
     */
    @PostMapping(value = "/oauth2/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revoke(
            @RequestParam("token") String token,
            @RequestParam(value = "token_type_hint", required = false) String hint) {
        // 无论命中与否，都尝试撤销 access 与 refresh；响应统一 200，不暴露存在性。
        tokenService.revokeAccessTokenByRaw(token);
        tokenService.revokeRefreshTokenByRaw(token);
        return ResponseEntity.ok().build();
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String errorCode) {
        return error(status, errorCode, null);
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String errorCode, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorCode);
        if (description != null) {
            body.put("error_description", description);
        }
        return ResponseEntity.status(status).body(body);
    }
}
