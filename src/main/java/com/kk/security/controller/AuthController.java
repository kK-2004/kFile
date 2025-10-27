package com.kk.security.controller;

import com.kk.security.entity.UserAccount;
import com.kk.security.repo.UserAccountRepository;
import com.kk.security.service.UserAccountService;
import com.kk.security.service.SiteUserLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserAccountService userAccountService;
    private final UserAccountRepository userAccountRepository;
    private final SiteUserLookupService siteUserLookupService;

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return ResponseEntity.status(401).body(Map.of("message", "未认证"));
        }
        Jwt jwt = jwtAuth.getToken();
        // 确保本地用户存在（若已存在则返回）
        UserAccount ua = userAccountService.findOrCreateByJwt(jwt,jwtAuth);

        // username: 优先从主站查询 nickName（若失败则退回本地缓存/claims）
        String username = null;
        try {
            username = siteUserLookupService.fetchNickNameByUserId(Long.parseLong(jwt.getSubject()));
        } catch (Exception ignored) {}
        if (username == null || username.isBlank()) {
            username = ua.getUsername();
            if (username == null || username.isBlank()) {
                username = jwt.getClaimAsString("preferred_username");
                if (username == null || username.isBlank()) username = jwt.getClaimAsString("name");
                if (username == null || username.isBlank()) username = jwt.getClaimAsString("email");
            }
        }
        if (username == null || username.isBlank()) username = "user-" + jwt.getSubject();

        // role: 使用本地角色映射为 SUPER/ADMIN/USER
        String local = ua.getRole() == null ? "KF_USER" : ua.getRole();
        String role;
        switch (local) {
            case "KF_SUPER" -> role = "SUPER";
            case "KF_ADMIN" -> role = "ADMIN";
            default -> role = "USER";
        }

        return ResponseEntity.ok(Map.of("username", username, "role", role));
    }
}
