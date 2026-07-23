package com.kk.security.oauth;

import com.kk.common.service.AppConfigService;
import com.kk.config.McpOAuthProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * OAuth client 注册（任务 4.1 / 4.2）。
 *
 * <ul>
 *   <li>预注册 public client 读取。</li>
 *   <li>RFC 7591 Dynamic Client Registration：限制为 Authorization Code + PKCE 所需的 public-client
 *       元数据（{@code token_endpoint_auth_method=none}）。</li>
 *   <li>字段白名单、重复注册去重、按 IP 限流、审计日志。</li>
 *   <li>拒绝非 localhost HTTP、非法 scheme 及格式错误的 redirect URI。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthClientService {

    private final OAuthClientRegistrationRepository clientRepo;
    private final McpOAuthProperties props;
    private final OAuthCrypto crypto;
    private final AppConfigService appConfigService;

    /** 内存级 DCR 限流（按 IP 窗口）。生产可换 Redis，单实例足够。 */
    private final Map<String, WindowCounter> rateLimitMap = new ConcurrentHashMap<>();

    /**
     * 动态注册 public client。仅接受允许的字段，其余忽略；redirect URI 校验后精确保存。
     * 相同 redirect URIs 的重复注册去重，返回既有 client。
     */
    @Transactional
    public Map<String, Object> registerDynamic(
            Map<String, Object> body, String clientIp) {
        // 字段白名单：只读 redirect_uris / client_name / grant_types / response_types /
        // token_endpoint_auth_method / scope。其余字段忽略，不写入也不报错。
        @SuppressWarnings("unchecked")
        List<String> redirectUrisRaw = (List<String>) body.get("redirect_uris");
        if (redirectUrisRaw == null || redirectUrisRaw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uris 必填");
        }
        List<String> redirectUris = normalizeAndValidateRedirectUris(redirectUrisRaw);

        // grant_types / response_types / auth_method 仅允许 Authorization Code + PKCE public 值
        // refresh_token 作为 grant_type 在注册时声明是合法的（客户端会用它刷新），允许
        validateStringList(body.get("grant_types"), Set.of("authorization_code", "refresh_token"), "grant_types");
        validateStringList(body.get("response_types"), Set.of("code"), "response_types");
        String authMethod = getAsString(body.get("token_endpoint_auth_method"), "none");
        if (!"none".equals(authMethod)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "token_endpoint_auth_method 仅支持 none（public client）");
        }
        String scope = getAsString(body.get("scope"), props.getScope());
        if (!props.getScope().equals(scope)) {
            // 仅允许配置的 scope
            scope = props.getScope();
        }
        String clientName = getAsString(body.get("client_name"), "dynamic-client");

        String redirectJson = toJsonArray(redirectUris);

        // 去重：优先按 client_name 匹配已有动态 client（agent 如 WorkBuddy 每次用相同名称，
        // 但 localhost 回调端口会变）。命中后更新 redirect_uri 为本次值，避免精确匹配失败。
        Optional<OAuthClientRegistration> byName =
                (clientName != null && !clientName.isBlank())
                        ? clientRepo.findByClientNameAndDynamicTrue(clientName)
                        : Optional.empty();
        if (byName.isPresent() && !byName.get().isDisabled()) {
            OAuthClientRegistration c = byName.get();
            if (!redirectJson.equals(c.getRedirectUrisJson())) {
                c.setRedirectUrisJson(redirectJson);
                clientRepo.save(c);
                log.info(
                        "BIZ action=OAUTH_DCR_UPDATE_REDIRECT clientId={} clientIp={} oldClientName={}",
                        c.getClientId(), clientIp, clientName);
            }
            log.info(
                    "BIZ action=OAUTH_DCR_DEDUP clientId={} clientIp={}", c.getClientId(), clientIp);
            return toRegistrationResponse(c, true);
        }

        // 兜底去重：相同 redirect URIs（无 client_name 时）
        Optional<OAuthClientRegistration> dup =
                clientRepo.findByRedirectUrisJsonAndDynamicTrue(redirectJson);
        if (dup.isPresent() && !dup.get().isDisabled()) {
            OAuthClientRegistration c = dup.get();
            log.info(
                    "BIZ action=OAUTH_DCR_DEDUP clientId={} clientIp={}", c.getClientId(), clientIp);
            return toRegistrationResponse(c, true);
        }

        // 仅对真正的新建 client 限流（去重命中不算）
        checkRateLimit(clientIp);

        // 新建
        String clientId = generateClientId();
        OAuthClientRegistration c = new OAuthClientRegistration();
        c.setClientId(clientId);
        c.setClientName(clientName);
        c.setRedirectUrisJson(redirectJson);
        c.setGrantTypes("[\"authorization_code\"]");
        c.setResponseTypes("[\"code\"]");
        c.setTokenEndpointAuthMethod("none");
        c.setScope(scope);
        c.setDynamic(true);
        c.setRegisteredFrom(clientIp);
        // 未使用 client 过期时间（清理任务用）
        c.setClientSecretExpiresAt(Instant.now().plus(props.getUnusedClientValidity()));
        c.setCreatedAt(Instant.now());
        c.setDisabled(false);
        c = clientRepo.save(c);

        log.info(
                "BIZ action=OAUTH_DCR_CREATE clientId={} clientName={} clientIp={} redirectUris={}",
                clientId, clientName, clientIp, redirectUris);
        return toRegistrationResponse(c, false);
    }

    /** 按 client_id 读取（授权请求校验用）。disabled 的返回空。 */
    @Transactional(readOnly = true)
    public Optional<OAuthClientRegistration> findByClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return clientRepo.findByClientId(clientId).filter(c -> !c.isDisabled());
    }

    /** 授权请求校验 redirect_uri 精确匹配（不做前缀匹配）。 */
    public void validateRedirectUriExact(OAuthClientRegistration client, String redirectUri) {
        if (redirectUri == null || !client.redirectUriSet().contains(redirectUri)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "redirect_uri 与注册值不精确匹配");
        }
    }

    /** 更新 client 最后使用时间。 */
    @Transactional
    public void touchLastUsed(OAuthClientRegistration client) {
        try {
            clientRepo.touchLastUsed(client.getId(), Instant.now());
        } catch (Exception ignored) {
        }
    }

    // ---- redirect URI 校验（任务 4.2）----

    /**
     * 校验每个 redirect URI。
     *
     * <p>规则（OAuth 2.0 / RFC 8252 本地 agent 场景）：
     * <ul>
     *   <li>http/https：localhost/127.0.0.1 可用 HTTP，其余必须 HTTPS</li>
     *   <li>自定义 scheme（如 {@code workbuddy://}、{@code http://127.0.0.1:port} loopback）：
     *       本地 agent 的回调，允许注册（精确匹配仍生效）</li>
     *   <li>拒绝无法解析或无 scheme 的值</li>
     * </ul>
     */
    List<String> normalizeAndValidateRedirectUris(List<String> raw) {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String uriStr : raw) {
            if (uriStr == null || uriStr.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri 不能为空");
            }
            String trimmed = uriStr.trim();
            URI uri;
            try {
                uri = URI.create(trimmed);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri 非法: " + trimmed);
            }
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri 缺少 scheme: " + trimmed);
            }
            String schemeLower = scheme.toLowerCase();
            // 自定义 scheme（非 http/https）：必须在管理后台配置的 scheme 白名单内（MCP_REDIRECT_ALLOWED_SCHEMES）。
            // 本地 agent 回调（如 workbuddy://...）由管理员显式放行；不在白名单的一律拒绝。
            if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
                List<String> allowedSchemes = appConfigService.getStringList(
                        AppConfigService.KEY_MCP_REDIRECT_ALLOWED_SCHEMES);
                if (allowedSchemes.stream().noneMatch(schemeLower::equals)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "redirect_uri 自定义协议未在白名单: " + schemeLower
                                    + "（联系管理员添加到 MCP_REDIRECT_ALLOWED_SCHEMES）");
                }
                if (!seen.add(trimmed)) continue;
                result.add(trimmed);
                continue;
            }
            // http/https：必须有 host
            String host = uri.getHost();
            if (host == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri 缺少 host: " + trimmed);
            }
            boolean isLocalhost = isLocalhost(host);
            if ("http".equals(schemeLower) && !isLocalhost) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "非 localhost 的 redirect_uri 必须使用 HTTPS: " + trimmed);
            }
            if (!seen.add(trimmed)) {
                continue; // 去重
            }
            result.add(trimmed);
        }
        return result;
    }

    private void checkRateLimit(String clientIp) {
        int limit = props.getDcrRateLimit();
        Duration window = props.getDcrRateWindow();
        WindowCounter counter = rateLimitMap.computeIfAbsent(clientIp, k -> new WindowCounter());
        if (!counter.tryAcquire(limit, window)) {
            log.warn("BIZ action=OAUTH_DCR_RATE_LIMIT clientIp={}", clientIp);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "动态注册请求过于频繁");
        }
    }

    // ---- helpers ----

    private String generateClientId() {
        return "mcp_" + crypto.generateOpaqueToken().substring(0, 24);
    }

    private Map<String, Object> toRegistrationResponse(OAuthClientRegistration c, boolean dedup) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_id", c.getClientId());
        m.put("client_name", c.getClientName());
        m.put("client_id_issued_at", c.getCreatedAt() == null ? null : c.getCreatedAt().toEpochMilli());
        m.put("redirect_uris", new ArrayList<>(c.redirectUriSet()));
        m.put("grant_types", List.of("authorization_code"));
        m.put("response_types", List.of("code"));
        m.put("token_endpoint_auth_method", c.getTokenEndpointAuthMethod());
        m.put("scope", c.getScope());
        return m;
    }

    private static void validateStringList(Object value, Set<String> allowed, String field) {
        if (value == null) {
            return; // 缺省即按默认
        }
        if (!(value instanceof List<?> list)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须是数组");
        }
        for (Object o : list) {
            if (o == null || !allowed.contains(o.toString())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, field + " 仅允许 " + allowed);
            }
        }
    }

    private static String getAsString(Object value, String def) {
        return value == null ? def : String.valueOf(value);
    }

    private static boolean isLocalhost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host);
    }

    static String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(items.get(i).replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }

    /** 简单滑动窗口计数器（线程安全）。 */
    private static final class WindowCounter {
        private final List<Long> timestamps = new ArrayList<>();
        long windowMillis = Duration.ofHours(1).toMillis();

        synchronized boolean tryAcquire(int limit, Duration window) {
            this.windowMillis = window.toMillis();
            long now = System.currentTimeMillis();
            timestamps.removeIf(t -> now - t > windowMillis);
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.add(now);
            return true;
        }
    }
}
