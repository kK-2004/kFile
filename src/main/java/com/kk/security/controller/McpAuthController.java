package com.kk.security.controller;

import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.service.McpTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * MCP 长期令牌的签发与管理。
 * - POST /api/mcp/login：账号密码登录，签发 6 个月 token（permitAll）。
 * - GET  /api/mcp/tokens：列出 token 元信息（SUPER 全部，否则仅自己的），不含明文。
 * - DELETE /api/mcp/tokens/{id}：吊销（SUPER 或所属用户）。
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpAuthController {
    private final McpTokenService tokenService;
    private final AdminUserRepository userRepo;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> payload) {
        String username = payload.getOrDefault("username", "");
        String password = payload.getOrDefault("password", "");
        try {
            McpTokenService.IssuedToken issued = tokenService.issue(username, password);
            // 明文 token 仅此处返回一次
            return ResponseEntity.ok(Map.of(
                    "accessToken", issued.accessToken(),
                    "expiresAt", issued.expiresAt(),
                    "tokenId", issued.tokenId()
            ));
        } catch (ResponseStatusException e) {
            // issue 内部用 AuthenticationManager 校验，失败抛异常 → 转 401
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "用户名或密码错误"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "用户名或密码错误"));
        }
    }

    @GetMapping("/tokens")
    public List<Map<String, Object>> listTokens() {
        AdminUser current = currentUser();
        return tokenService.listForUser(current).stream().map(tokenService::toView).toList();
    }

    /**
     * OAuth 风格网页授权：基于当前 session 登录用户签发 token（不再重输密码）。
     * 前端授权页调用此端点，拿到 token 后拼 {redirect_uri}?token=<明文> 跳转给 agent。
     * 需 session 认证（非 permitAll）。
     */
    @PostMapping("/authorize")
    public Map<String, Object> authorize(@RequestBody Map<String, String> payload) {
        String redirectUri = payload.getOrDefault("redirect_uri", "");
        tokenService.validateRedirectUri(redirectUri); // 不合法抛 400
        AdminUser current = currentUser();
        McpTokenService.IssuedToken issued = tokenService.issueForCurrentUser(current);
        // 返回 token + redirectUri，由前端拼最终跳转 URL
        return Map.of(
                "accessToken", issued.accessToken(),
                "expiresAt", issued.expiresAt(),
                "tokenId", issued.tokenId(),
                "redirectUri", redirectUri
        );
    }

    @DeleteMapping("/tokens/{id}")
    public void revoke(@PathVariable Long id) {
        tokenService.revoke(id, currentUser());
    }

    private AdminUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userRepo.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
    }
}
