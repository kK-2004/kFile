package com.kk.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.common.service.AppConfigService;
import com.kk.config.McpOAuthProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务 4.2：DCR redirect URI 校验。覆盖：
 * <ul>
 *   <li>非 localhost HTTP redirect → 拒绝</li>
 *   <li>非法 scheme → 拒绝</li>
 *   <li>格式错误 URL → 拒绝</li>
 *   <li>localhost HTTP → 允许</li>
 *   <li>HTTPS → 允许</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthClientServiceTest {

    @Mock private OAuthClientRegistrationRepository clientRepo;
    @Mock private AppConfigService appConfigService;
    private McpOAuthProperties props;
    private OAuthCrypto crypto;
    private OAuthClientService clientService;

    @BeforeEach
    void setUp() {
        props = new McpOAuthProperties();
        props.setPublicBaseUrl("https://file.example.com");
        props.setMcpEndpoint("/mcp");
        props.setScope("mcp:tools");
        props.setDcrRateLimit(100); // 测试不限流
        crypto = new OAuthCrypto();
        // 默认：自定义 scheme 白名单为空（仅 http/https）
        lenient().when(appConfigService.getStringList(AppConfigService.KEY_MCP_REDIRECT_ALLOWED_SCHEMES))
                .thenReturn(List.of());
        clientService =
                new OAuthClientService(clientRepo, props, crypto, appConfigService);
    }

    @Test
    void nonLocalhostHttpRedirect_rejected() {
        assertThatThrownBy(
                        () ->
                                clientService.normalizeAndValidateRedirectUris(
                                        List.of("http://attacker.com/callback")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void illegalScheme_notInWhitelist_rejected() {
        // ftp 不在白名单 → 拒绝
        assertThatThrownBy(
                        () ->
                                clientService.normalizeAndValidateRedirectUris(
                                        List.of("ftp://localhost/callback")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未在白名单");
    }

    @Test
    void customLocalScheme_inWhitelist_allowed() {
        // WorkBuddy 自定义协议在白名单内 → 允许
        when(appConfigService.getStringList(AppConfigService.KEY_MCP_REDIRECT_ALLOWED_SCHEMES))
                .thenReturn(List.of("workbuddy"));
        List<String> result =
                clientService.normalizeAndValidateRedirectUris(
                        List.of("workbuddy://workbuddy/mcp/custom-mcp:kfile/oauth/callback"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).startsWith("workbuddy://");
    }

    @Test
    void customLocalScheme_notInWhitelist_rejected() {
        // 白名单为空时，自定义协议一律拒绝
        assertThatThrownBy(
                        () ->
                                clientService.normalizeAndValidateRedirectUris(
                                        List.of("workbuddy://workbuddy/callback")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未在白名单");
    }

    @Test
    void malformedUrl_rejected() {
        assertThatThrownBy(
                        () -> clientService.normalizeAndValidateRedirectUris(List.of("not a url")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void localhostHttp_allowed() {
        List<String> result =
                clientService.normalizeAndValidateRedirectUris(List.of("http://localhost:3000/callback"));
        assertThat(result).containsExactly("http://localhost:3000/callback");
    }

    @Test
    void httpsAllowed() {
        List<String> result =
                clientService.normalizeAndValidateRedirectUris(List.of("https://app.example.com/cb"));
        assertThat(result).containsExactly("https://app.example.com/cb");
    }

    @Test
    void duplicateRedirectUris_deduped() {
        List<String> result =
                clientService.normalizeAndValidateRedirectUris(
                        List.of("https://a.com/cb", "https://a.com/cb"));
        assertThat(result).hasSize(1);
    }

    @Test
    void registerDynamic_createsClientWithExactRedirectUris() {
        when(clientRepo.findByClientNameAndDynamicTrueAndDisabledFalseOrderByIdAsc(any()))
                .thenReturn(List.of());
        when(clientRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body =
                Map.of(
                        "redirect_uris", List.of("http://localhost:3000/callback"),
                        "client_name", "test-agent");
        Map<String, Object> resp = clientService.registerDynamic(body, "127.0.0.1");

        assertThat(resp.get("client_id")).asString().startsWith("mcp_");
        assertThat(resp.get("token_endpoint_auth_method")).isEqualTo("none");
        assertThat(resp.get("grant_types")).isEqualTo(List.of("authorization_code"));
        @SuppressWarnings("unchecked")
        List<String> rus = (List<String>) resp.get("redirect_uris");
        assertThat(rus).containsExactly("http://localhost:3000/callback");
    }

    @Test
    void registerDynamic_rejectsNonPublicAuthMethod() {
        Map<String, Object> body =
                Map.of(
                        "redirect_uris", List.of("http://localhost:3000/callback"),
                        "token_endpoint_auth_method", "client_secret_basic");
        assertThatThrownBy(() -> clientService.registerDynamic(body, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("none");
    }

    @Test
    void registerDynamic_ignoresDisallowedGrantType() {
        Map<String, Object> body =
                Map.of(
                        "redirect_uris", List.of("http://localhost:3000/callback"),
                        "grant_types", List.of("password"));
        // 不允许的 grant_type 应在写入前被拒绝（无需 stub，因为校验先于 save）
        assertThatThrownBy(() -> clientService.registerDynamic(body, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ---- loopback 端口无关匹配（RFC 8252）与同名去重 ----

    private static OAuthClientRegistration dynamicClient(String clientId, String name, String... uris) {
        OAuthClientRegistration c = new OAuthClientRegistration();
        c.setClientId(clientId);
        c.setClientName(name);
        c.setRedirectUrisJson(OAuthClientService.toJsonArray(List.of(uris)));
        c.setDynamic(true);
        c.setTokenEndpointAuthMethod("none");
        return c;
    }

    @Test
    void loopbackRedirect_matchesAnyPort() {
        OAuthClientRegistration c = dynamicClient("mcp_a", "Claude Code", "http://localhost:59681/callback");
        assertThat(c.matchesRedirectUri("http://localhost:61234/callback")).isTrue();
        assertThat(c.matchesRedirectUri("http://localhost/callback")).isTrue();
        // path、host、scheme 仍须一致
        assertThat(c.matchesRedirectUri("http://localhost:61234/other")).isFalse();
        assertThat(c.matchesRedirectUri("http://127.0.0.1:61234/callback")).isFalse();
        assertThat(c.matchesRedirectUri("https://localhost:61234/callback")).isFalse();
        assertThat(c.matchesRedirectUri("http://localhost:61234/callback?x=1")).isFalse();
        assertThat(c.matchesRedirectUri(null)).isFalse();
    }

    @Test
    void ipv6LoopbackRedirect_matchesAnyPort() {
        OAuthClientRegistration c = dynamicClient("mcp_a", "agent", "http://[::1]:5000/cb");
        assertThat(c.matchesRedirectUri("http://[::1]:6000/cb")).isTrue();
    }

    @Test
    void nonLoopbackRedirect_stillExact() {
        OAuthClientRegistration c =
                dynamicClient("mcp_a", "agent", "https://app.example.com:8443/cb", "workbuddy://wb/callback");
        assertThat(c.matchesRedirectUri("https://app.example.com:8443/cb")).isTrue();
        assertThat(c.matchesRedirectUri("https://app.example.com:9443/cb")).isFalse();
        assertThat(c.matchesRedirectUri("workbuddy://wb/callback")).isTrue();
        assertThat(c.matchesRedirectUri("workbuddy://wb/callback2")).isFalse();
    }

    @Test
    void validateRedirectUriExact_loopbackPortChange_allowed() {
        OAuthClientRegistration c = dynamicClient("mcp_a", "Claude Code", "http://localhost:5000/callback");
        clientService.validateRedirectUriExact(c, "http://localhost:6000/callback");
        assertThatThrownBy(() -> clientService.validateRedirectUriExact(c, "http://localhost:6000/evil"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void registerDynamic_sameNameLoopbackPortChange_reusesClientWithoutOverwriting() {
        OAuthClientRegistration existing =
                dynamicClient("mcp_existing", "Claude Code", "http://localhost:5000/callback");
        when(clientRepo.findByClientNameAndDynamicTrueAndDisabledFalseOrderByIdAsc("Claude Code"))
                .thenReturn(List.of(existing));

        Map<String, Object> resp =
                clientService.registerDynamic(
                        Map.of(
                                "redirect_uris", List.of("http://localhost:6000/callback"),
                                "client_name", "Claude Code"),
                        "1.2.3.4");

        assertThat(resp.get("client_id")).isEqualTo("mcp_existing");
        assertThat(resp.get("redirect_uris")).isEqualTo(List.of("http://localhost:6000/callback"));
        // 已注册值不被改写：并发授权中的另一端口仍可通过校验
        assertThat(existing.getRedirectUrisJson()).isEqualTo("[\"http://localhost:5000/callback\"]");
        assertThat(existing.matchesRedirectUri("http://localhost:5000/callback")).isTrue();
        verify(clientRepo, never()).save(any());
    }

    @Test
    void registerDynamic_sameNameDifferentRedirect_createsNewClient() {
        OAuthClientRegistration existing =
                dynamicClient("mcp_existing", "Claude Code", "http://localhost:5000/callback");
        when(clientRepo.findByClientNameAndDynamicTrueAndDisabledFalseOrderByIdAsc("Claude Code"))
                .thenReturn(List.of(existing));
        when(clientRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 冒用同名但回调指向外部域名：不得复用/改写原 client
        Map<String, Object> resp =
                clientService.registerDynamic(
                        Map.of(
                                "redirect_uris", List.of("https://evil.example.com/cb"),
                                "client_name", "Claude Code"),
                        "1.2.3.4");

        assertThat(resp.get("client_id")).isNotEqualTo("mcp_existing");
        assertThat(existing.getRedirectUrisJson()).isEqualTo("[\"http://localhost:5000/callback\"]");
    }
}
