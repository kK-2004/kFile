package com.kk.security.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private com.kk.security.repo.AdminUserRepository userRepo;
    @Autowired
    private SecurityContextRepository securityContextRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> payload,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        String username = payload.getOrDefault("username", "");
        String password = payload.getOrDefault("password", "");
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            // ensure session persistence
            securityContextRepository.saveContext(context, request, response);
            return ResponseEntity.ok(Map.of("username", username));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.ok(Map.of());
        }
        var user = userRepo.findByUsername(auth.getName()).orElse(null);
        String role = user != null ? user.getRole() : "";
        return ResponseEntity.ok(Map.of("username", auth.getName(), "role", role));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> payload) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).body(Map.of("message", "无权限，请登录"));
        }
        String username = auth.getName();
        String current = payload.getOrDefault("currentPassword", "");
        String next = payload.getOrDefault("newPassword", "");
        if (next == null || next.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "新密码长度至少6位"));
        }
        var user = userRepo.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(current, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("message", "原密码错误"));
        }
        user.setPassword(passwordEncoder.encode(next));
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("message", "修改成功"));
    }
}
