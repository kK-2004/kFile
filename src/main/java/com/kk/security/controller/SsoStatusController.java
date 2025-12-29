package com.kk.security.controller;

import com.kk.security.service.SsoStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sso")
@RequiredArgsConstructor
public class SsoStatusController {
    private final SsoStatusService ssoStatusService;

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(ssoStatusService.status());
    }
}

