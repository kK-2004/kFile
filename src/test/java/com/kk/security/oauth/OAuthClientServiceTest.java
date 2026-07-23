package com.kk.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.kk.common.service.AppConfigService;
import com.kk.config.McpOAuthProperties;
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
        when(clientRepo.findByRedirectUrisJsonAndDynamicTrue(any())).thenReturn(Optional.empty());
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
}
