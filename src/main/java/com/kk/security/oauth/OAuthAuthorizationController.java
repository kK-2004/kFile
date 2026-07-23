package com.kk.security.oauth;

import com.kk.security.entity.AdminUser;
import com.kk.security.oauth.OAuthAuthorizationService.AuthorizationRequest;
import com.kk.security.repo.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OAuth 授权与 Dynamic Client Registration 的 HTTP 入口（任务 4.1 / 4.3 / 4.4 / 4.5 / 4.6）。
 *
 * <ul>
 *   <li>{@code POST /oauth2/register} — RFC 7591 DCR（任务 4.1 / 4.2）。</li>
 *   <li>{@code GET /oauth2/authorize} — 授权请求校验；未登录引导管理员 session 登录后恢复（任务 4.3 / 4.4）。</li>
 *   <li>{@code POST /oauth2/consent} — consent 页面，处理批准/拒绝（任务 4.5 / 4.6）。</li>
 * </ul>
 *
 * <p>未登录时不会向 redirect URI 重定向，而是安全保存授权请求并跳转到管理员登录页，登录成功后由前端
 * 携带 return 参数恢复同一个授权请求。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class OAuthAuthorizationController {

    private final OAuthClientService clientService;
    private final OAuthAuthorizationService authorizationService;
    private final AdminUserRepository userRepo;

    /** 前端 SPA 基址（env.cors），用于拼登录页绝对 URL（未登录时浏览器 302 跳转）。 */
    @org.springframework.beans.factory.annotation.Value("${env.cors:}")
    private String frontendBaseUrl;

    /** 管理员登录页：前端 SPA 的 /admin/login（生产前后端同域，本地开发在前端端口）。 */
    private String loginPageUrl() {
        String base = frontendBaseUrl == null || frontendBaseUrl.isBlank() ? "" : frontendBaseUrl;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/admin/login";
    }

    // ============ 任务 4.1 / 4.2：Dynamic Client Registration ============

    @PostMapping("/oauth2/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String ip = resolveClientIp(request);
        Map<String, Object> result = clientService.registerDynamic(body, ip);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    // ============ 任务 4.3 / 4.4：授权请求校验 + 登录引导 ============

    /**
     * 授权请求入口（标准 OAuth：浏览器/agent 直接 GET 此端点）。
     *
     * <p>语义：
     * <ul>
     *   <li>先校验全部安全参数（client/scope/resource/state/redirect/PKCE）。任何校验失败返回 400，
     *       绝不向未验证 redirect URI 重定向。</li>
     *   <li>未登录：302 重定向到前端 consent 页（携带原 authorize 参数）。agent 在系统浏览器打开
     *       本端点时，浏览器跟随 302 到前端 consent 页，由前端 SPA 编排登录→授权（方案 B）。</li>
     *   <li>已登录：返回 {@code 200} + 授权请求详情（供 consent 页通过 axios 调用渲染）。</li>
     * </ul>
     *
     * <p>注意：agent 必须在系统浏览器（而非 HTTP 客户端）打开本端点，302 才能触发浏览器导航。
     */
    @GetMapping("/oauth2/authorize")
    public ResponseEntity<?> authorize(
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "resource", required = false) String resource,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false)
                    String codeChallengeMethod,
            HttpServletRequest httpRequest) {
        // 校验全部参数（失败抛 400，绝不重定向到未验证 URI）
        AuthorizationRequest req =
                authorizationService.validateRequest(
                        clientId,
                        redirectUri,
                        responseType,
                        state,
                        scope,
                        resource,
                        codeChallenge,
                        codeChallengeMethod);

        AdminUser current = currentUserOrNull();
        String consentUrl =
                loginPageUrl().replace("/admin/login", "/admin/mcp/authorize")
                        + buildAuthorizeQuery(
                                clientId, redirectUri, responseType, state, scope, resource,
                                codeChallenge, codeChallengeMethod);

        // 浏览器直接访问（Accept: text/html）：无论登录与否都 302 跳前端 consent 页，
        // 由前端编排登录检测 + 渲染授权确认 UI。
        // 前端 axios 调用（Accept: application/json）：返回 JSON（未登录 401 / 已登录 consent 详情）。
        boolean browserNav = wantsBrowserNav(httpRequest);
        if (browserNav) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", consentUrl)
                    .build();
        }

        if (current == null) {
            // 前端 axios 调用 + 未登录：返回 401 + authorizeUrl，前端据此跳登录页
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("loginRequired", true, "authorizeUrl", consentUrl));
        }

        // 前端 axios 调用 + 已登录：返回 consent 详情 JSON
        return ResponseEntity.ok(toConsentView(req, current));
    }

    /**
     * 判断是否浏览器直接导航（用户在地址栏访问 / agent 用系统浏览器打开）。
     * 前端 axios 请求 Accept 为 application/json；浏览器导航 Accept 含 text/html。
     */
    private boolean wantsBrowserNav(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null) {
            return true; // 无 Accept 头，按浏览器导航处理
        }
        return accept.contains("text/html");
    }

    // ============ 任务 4.5 / 4.6：consent 批准 / 拒绝 ============

    /**
     * consent 处理。请求体包含全部授权参数 + decision(approve/deny)。需 session 认证。
     * 任何参数校验失败返回 400，不重定向。成功返回 redirect URL（前端执行跳转）。
     */
    @PostMapping("/oauth2/consent")
    public ResponseEntity<Map<String, Object>> consent(@RequestBody Map<String, Object> body) {
        AdminUser current =
                currentUserOrNull() == null
                        ? requireLogin()
                        : currentUserOrNull();
        AuthorizationRequest req =
                authorizationService.validateRequest(
                        getStr(body, "client_id"),
                        getStr(body, "redirect_uri"),
                        getStr(body, "response_type"),
                        getStr(body, "state"),
                        getStr(body, "scope"),
                        getStr(body, "resource"),
                        getStr(body, "code_challenge"),
                        getStr(body, "code_challenge_method"));

        String decision = getStr(body, "decision");
        String redirectUrl;
        if ("approve".equalsIgnoreCase(decision)) {
            redirectUrl = authorizationService.approve(req, current);
        } else {
            redirectUrl = authorizationService.deny(req);
        }
        return ResponseEntity.ok(Map.of("redirect", redirectUrl));
    }

    // ============ helpers ============

    private Map<String, Object> toConsentView(AuthorizationRequest req, AdminUser user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientId", req.clientId());
        m.put("clientName", req.client().getClientName());
        m.put("redirectUri", req.redirectUri());
        m.put("scope", req.scope());
        m.put("resource", req.resource());
        m.put("state", req.state());
        m.put("username", user.getUsername());
        return m;
    }

    /** 构造 consent 页的 query 串（? 开头），参数名与前端 AdminMcpAuthorize.vue 读取的 route.query 一致。 */
    private String buildAuthorizeQuery(
            String clientId,
            String redirectUri,
            String responseType,
            String state,
            String scope,
            String resource,
            String codeChallenge,
            String codeChallengeMethod) {
        StringBuilder sb = new StringBuilder("?");
        appendQuery(sb, "client_id", clientId);
        appendQuery(sb, "redirect_uri", redirectUri);
        appendQuery(sb, "response_type", responseType);
        appendQuery(sb, "state", state);
        appendQuery(sb, "scope", scope);
        appendQuery(sb, "resource", resource);
        appendQuery(sb, "code_challenge", codeChallenge);
        appendQuery(sb, "code_challenge_method", codeChallengeMethod);
        return sb.toString();
    }

    private void appendQuery(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 1) {
            sb.append("&");
        }
        sb.append(name)
                .append("=")
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private AdminUser currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null
                || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepo.findByUsername(auth.getName()).orElse(null);
    }

    private AdminUser requireLogin() {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }

    private static String getStr(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        String xrip = request.getHeader("X-Real-IP");
        if (xrip != null && !xrip.isBlank()) {
            return xrip.trim();
        }
        return request.getRemoteAddr();
    }
}
