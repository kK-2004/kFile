package com.kk.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.kk.config.McpOAuthProperties;
import com.kk.security.entity.AdminUser;
import com.kk.security.oauth.OAuthTokenService.InvalidGrantException;
import com.kk.security.oauth.OAuthTokenService.IssuedTokens;
import com.kk.security.oauth.OAuthTokenService.ReuseDetectedException;
import com.kk.security.repo.AdminUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 任务 5.5：OAuth 协议测试。覆盖成功换取、code 重用、错误 verifier、错误 redirect/resource/client、
 * 过期、刷新轮换、重放检测与撤销。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthTokenServiceTest {

    @Mock private OAuthAuthorizationCodeRepository codeRepo;
    @Mock private OAuthAccessTokenRepository accessRepo;
    @Mock private OAuthRefreshTokenRepository refreshRepo;
    @Mock private OAuthAuthorizationGrantRepository grantRepo;
    @Mock private OAuthClientRegistrationRepository clientRegistrationRepo;
    @Mock private AdminUserRepository userRepo;

    private OAuthCrypto crypto;
    private McpOAuthProperties props;
    private OAuthTokenService tokenService;

    private final AdminUser user = newUser(1L, "alice", "SUPER");
    private static final String CLIENT_ID = "mcp_test";
    private static final String REDIRECT_URI = "http://localhost:3000/cb";
    private static final String RESOURCE = "https://file.example.com/mcp";
    private static final String SCOPE = "mcp:tools";
    private String codeChallenge;
    private String codeVerifier;

    // in-memory stores to emulate repo persistence for the lifecycle flows
    private final Map<String, OAuthAuthorizationCode> codeStore = new HashMap<>();
    private final Map<String, OAuthRefreshToken> refreshStore = new HashMap<>();
    private final List<OAuthAccessToken> accessStore = new ArrayList<>();
    private final List<OAuthAuthorizationGrant> grantStore = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        crypto = new OAuthCrypto();
        props = new McpOAuthProperties();
        props.setPublicBaseUrl("https://file.example.com");
        props.setMcpEndpoint("/mcp");
        props.setScope(SCOPE);
        props.setAccessTokenValidity(Duration.ofMinutes(15));
        props.setRefreshTokenValidity(Duration.ofDays(30));
        props.setAuthorizationCodeValidity(Duration.ofMinutes(5));
        tokenService =
                new OAuthTokenService(
                        codeRepo, accessRepo, refreshRepo, grantRepo, clientRegistrationRepo, crypto,
                        props, userRepo);

        // PKCE pair
        codeVerifier = "verifier-1234567890abcdef-verifier-1234567890abcdef";
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        codeChallenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        // default stubs
        lenient().when(clientRegistrationRepo.findByClientId(anyString())).thenReturn(Optional.of(registration()));
        lenient().when(codeRepo.findByCodeHash(anyString())).thenAnswer(inv -> Optional.ofNullable(codeStore.get(inv.getArgument(0))));
        lenient().when(codeRepo.consume(anyLong(), any())).thenAnswer(inv -> {
            OAuthAuthorizationCode c = findCodeById(inv.getArgument(0));
            if (c != null && !c.isConsumed()) { c.setConsumed(true); c.setConsumedAt(Instant.now()); return 1; }
            return 0;
        });
        lenient().when(refreshRepo.findByTokenHash(anyString())).thenAnswer(inv -> Optional.ofNullable(refreshStore.get(inv.getArgument(0))));
        lenient().when(refreshRepo.consume(anyLong(), any())).thenAnswer(inv -> {
            OAuthRefreshToken r = findRefreshById(inv.getArgument(0));
            if (r != null && !r.isConsumed()) { r.setConsumed(true); r.setConsumedAt(Instant.now()); return 1; }
            return 0;
        });
        lenient().when(refreshRepo.save(any())).thenAnswer(inv -> { OAuthRefreshToken r = inv.getArgument(0); if (r.getId()==null) r.setId((long)(refreshStore.size()+1000)); refreshStore.put(r.getTokenHash(), r); return r; });
        lenient().when(accessRepo.save(any())).thenAnswer(inv -> { OAuthAccessToken t = inv.getArgument(0); accessStore.add(t); return t; });
        lenient().when(grantRepo.save(any())).thenAnswer(inv -> { OAuthAuthorizationGrant g = inv.getArgument(0); if (g.getId()==null) g.setId((long)(grantStore.size()+1)); grantStore.add(g); return g; });
        lenient().when(grantRepo.revoke(anyLong(), any(), anyString())).thenAnswer(inv -> {
            OAuthAuthorizationGrant g = findGrantById(inv.getArgument(0));
            if (g != null && !g.isRevoked()) { g.setRevoked(true); g.setRevokedAt(inv.getArgument(1)); return 1; }
            return 0;
        });
        lenient().when(accessRepo.revokeByGrant(anyLong(), any())).thenAnswer(inv -> { accessStore.forEach(t -> { if (t.getGrant()!=null && t.getGrant().getId().equals(inv.getArgument(0))) t.setRevoked(true); }); return 1; });
        lenient().when(refreshRepo.revokeByGrant(anyLong(), any())).thenAnswer(inv -> { refreshStore.values().forEach(r -> { if (r.getGrant()!=null && r.getGrant().getId().equals(inv.getArgument(0))) r.setRevoked(true); }); return 1; });
        lenient().when(accessRepo.touchLastUsed(anyLong(), any())).thenReturn(1);
        lenient().when(accessRepo.findByTokenHash(anyString())).thenAnswer(inv -> accessStore.stream().filter(t -> t.getTokenHash().equals(inv.getArgument(0))).findFirst());
    }

    // ---------- 5.1 authorization code grant ----------

    @Test
    void grantWithCode_success() {
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        IssuedTokens issued =
                tokenService.grantWithAuthorizationCode(
                        rawCode, CLIENT_ID, REDIRECT_URI, RESOURCE, codeVerifier);
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.refreshToken()).isNotBlank();
        assertThat(issued.grant().getResourceUri()).isEqualTo(RESOURCE);
        assertThat(issued.grant().getScope()).isEqualTo(SCOPE);
    }

    @Test
    void grantWithCode_codeReuse_rejected() {
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        tokenService.grantWithAuthorizationCode(rawCode, CLIENT_ID, REDIRECT_URI, RESOURCE, codeVerifier);
        // 第二次用同一 code → 拒绝
        assertThatThrownBy(
                        () ->
                                tokenService.grantWithAuthorizationCode(
                                        rawCode, CLIENT_ID, REDIRECT_URI, RESOURCE, codeVerifier))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("已被使用");
    }

    @Test
    void grantWithCode_wrongVerifier_rejected() {
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        assertThatThrownBy(
                        () ->
                                tokenService.grantWithAuthorizationCode(
                                        rawCode, CLIENT_ID, REDIRECT_URI, RESOURCE, "wrong-verifier"))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("verifier");
    }

    @Test
    void grantWithCode_wrongRedirect_dynamicClient_tolerated() {
        // 动态注册 client（DCR）在 PKCE 兜底下宽容接受 redirect_uri 不一致
        // （兼容 WorkBuddy 等 agent 在 token exchange 用不同 scheme 回调）
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        OAuthClientRegistration dynamicReg = registration();
        dynamicReg.setDynamic(true);
        org.mockito.Mockito.when(clientRegistrationRepo.findByClientId(CLIENT_ID))
                .thenReturn(Optional.of(dynamicReg));
        IssuedTokens issued =
                tokenService.grantWithAuthorizationCode(
                        rawCode, CLIENT_ID, "http://localhost:3000/other", RESOURCE, codeVerifier);
        assertThat(issued.accessToken()).isNotBlank();
    }

    @Test
    void grantWithCode_wrongRedirect_preregisteredClient_rejected() {
        // 预注册（非动态）client 仍严格校验 redirect_uri
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        OAuthClientRegistration staticReg = registration();
        staticReg.setDynamic(false);
        org.mockito.Mockito.when(clientRegistrationRepo.findByClientId(CLIENT_ID))
                .thenReturn(Optional.of(staticReg));
        assertThatThrownBy(
                        () ->
                                tokenService.grantWithAuthorizationCode(
                                        rawCode, CLIENT_ID, "http://localhost:3000/other", RESOURCE, codeVerifier))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    void grantWithCode_wrongResource_rejected() {
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        assertThatThrownBy(
                        () ->
                                tokenService.grantWithAuthorizationCode(
                                        rawCode, CLIENT_ID, REDIRECT_URI, "https://other.com/mcp", codeVerifier))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("resource");
    }

    @Test
    void grantWithCode_wrongClient_rejected() {
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        assertThatThrownBy(
                        () ->
                                tokenService.grantWithAuthorizationCode(
                                        rawCode, "mcp_other", REDIRECT_URI, RESOURCE, codeVerifier))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("client");
    }

    @Test
    void grantWithCode_expired_rejected() {
        String rawCode = seedCode(null, Instant.now().minus(Duration.ofMinutes(1)));
        assertThatThrownBy(
                        () ->
                                tokenService.grantWithAuthorizationCode(
                                        rawCode, CLIENT_ID, REDIRECT_URI, RESOURCE, codeVerifier))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("过期");
    }

    // ---------- 5.2 refresh rotation + reuse detection ----------

    @Test
    void refresh_rotation_oldBecomesInvalid() {
        IssuedTokens first = exchangeViaCode();
        IssuedTokens rotated =
                tokenService.grantWithRefreshToken(first.refreshToken(), CLIENT_ID);
        assertThat(rotated.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(first.refreshToken());
        // 旧 refresh token 再次使用 → 整族吊销
        assertThatThrownBy(
                        () -> tokenService.grantWithRefreshToken(first.refreshToken(), CLIENT_ID))
                .isInstanceOf(ReuseDetectedException.class);
        // 整族吊销后，新 refresh token 也失效
        assertThatThrownBy(
                        () -> tokenService.grantWithRefreshToken(rotated.refreshToken(), CLIENT_ID))
                .isInstanceOf(InvalidGrantException.class);
    }

    @Test
    void refresh_wrongClient_rejected() {
        IssuedTokens first = exchangeViaCode();
        assertThatThrownBy(() -> tokenService.grantWithRefreshToken(first.refreshToken(), "mcp_other"))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("client");
    }

    // ---------- revocation ----------

    @Test
    void revokeAccessToken_invalidatesIt() {
        IssuedTokens issued = exchangeViaCode();
        boolean revoked = tokenService.revokeAccessTokenByRaw(issued.accessToken());
        assertThat(revoked).isTrue();
        // 校验：撤销后 access token 不再有效
        Optional<OAuthTokenService.AccessTokenContext> ctx =
                tokenService.validateAccessToken(issued.accessToken(), RESOURCE);
        assertThat(ctx).isEmpty();
    }

    @Test
    void revokeAccessToken_unknownToken_returnsFalseButNoException() {
        assertThat(tokenService.revokeAccessTokenByRaw("nonexistent")).isFalse();
    }

    @Test
    void revokeGrant_invalidatesAllTokens() {
        IssuedTokens issued = exchangeViaCode();
        tokenService.revokeGrant(issued.grant(), "user_revoked");
        assertThat(tokenService.validateAccessToken(issued.accessToken(), RESOURCE)).isEmpty();
    }

    @Test
    void accessValidation_wrongResource_rejected() {
        IssuedTokens issued = exchangeViaCode();
        // 为其他 resource 签发 → 当前 MCP resource 校验失败
        assertThat(tokenService.validateAccessToken(issued.accessToken(), "https://other.com/mcp"))
                .isEmpty();
    }

    @Test
    void accessValidation_disabledUser_rejected() {
        IssuedTokens issued = exchangeViaCode();
        user.setEnabled(false);
        assertThat(tokenService.validateAccessToken(issued.accessToken(), RESOURCE)).isEmpty();
        user.setEnabled(true);
    }

    // ---------- helpers ----------

    private IssuedTokens exchangeViaCode() {
        String rawCode = seedCode(null, Instant.now().plus(Duration.ofMinutes(5)));
        return tokenService.grantWithAuthorizationCode(
                rawCode, CLIENT_ID, REDIRECT_URI, RESOURCE, codeVerifier);
    }

    /** 种入一个未消费 code，返回明文 code。 */
    private String seedCode(Long id, Instant expiresAt) {
        String rawCode = crypto.generateAuthorizationCode();
        OAuthAuthorizationCode c = new OAuthAuthorizationCode();
        c.setId(id == null ? (long) (codeStore.size() + 1) : id);
        c.setCodeHash(crypto.sha256Hex(rawCode));
        c.setUser(user);
        c.setClientId(CLIENT_ID);
        c.setRedirectUri(REDIRECT_URI);
        c.setResourceUri(RESOURCE);
        c.setScope(SCOPE);
        c.setCodeChallenge(codeChallenge);
        c.setCreatedAt(Instant.now());
        c.setExpiresAt(expiresAt);
        c.setConsumed(false);
        codeStore.put(c.getCodeHash(), c);
        return rawCode;
    }

    private OAuthAuthorizationCode findCodeById(Long id) {
        return codeStore.values().stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    private OAuthRefreshToken findRefreshById(Long id) {
        return refreshStore.values().stream().filter(r -> r.getId() != null && r.getId().equals(id)).findFirst().orElse(null);
    }

    private OAuthAuthorizationGrant findGrantById(Long id) {
        return grantStore.stream().filter(g -> g.getId().equals(id)).findFirst().orElse(null);
    }

    private static AdminUser newUser(Long id, String username, String role) {
        AdminUser u = new AdminUser();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setEnabled(true);
        return u;
    }

    private static OAuthClientRegistration registration() {
        OAuthClientRegistration r = new OAuthClientRegistration();
        r.setId(1L);
        r.setClientId(CLIENT_ID);
        r.setClientName("test-client");
        r.setRedirectUrisJson("[\"" + REDIRECT_URI + "\"]");
        r.setScope(SCOPE);
        r.setDynamic(true);
        return r;
    }
}
