package com.kk.security.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SsoStatusService {
    @Value("${app.sso.enabled:true}")
    private boolean ssoEnabled;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;

    @Value("${app.sso.probe.timeout-ms:1200}")
    private int probeTimeoutMs;

    @Value("${app.sso.probe.ok-cache-ms:5000}")
    private long okCacheMs;

    @Value("${app.sso.probe.fail-backoff-ms:30000}")
    private long failBackoffMs;

    private final RestTemplate restTemplate;
    private final AtomicReference<Map<String, Object>> cached = new AtomicReference<>();
    private final AtomicReference<Instant> nextProbeAt = new AtomicReference<>(Instant.EPOCH);

    public SsoStatusService() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1200);
        factory.setReadTimeout(1200);
        this.restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> status() {
        if (!ssoEnabled) {
            return Map.of(
                    "enabled", false,
                    "available", false,
                    "message", "SSO disabled"
            );
        }
        if (issuerUri == null || issuerUri.isBlank()) {
            return Map.of(
                    "enabled", true,
                    "available", false,
                    "message", "SSO issuer-uri not configured"
            );
        }

        // Sync RestTemplate timeouts with config
        try {
            var rf = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(Math.max(100, probeTimeoutMs));
            rf.setReadTimeout(Math.max(100, probeTimeoutMs));
            restTemplate.setRequestFactory(rf);
        } catch (Exception ignored) {}

        Instant now = Instant.now();
        Map<String, Object> c = cached.get();
        if (c != null) {
            Instant next = nextProbeAt.get();
            if (now.isBefore(next)) return c;
        }

        String base = issuerUri.replaceAll("/+$", "");
        String url = base + "/.well-known/openid-configuration";
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            Map<String, Object> out = Map.of(
                    "enabled", true,
                    "available", ok,
                    "issuer", issuerUri,
                    "message", ok ? "ok" : ("bad status: " + resp.getStatusCode().value())
            );
            cached.set(out);
            nextProbeAt.set(now.plus(Duration.ofMillis(ok ? okCacheMs : failBackoffMs)));
            return out;
        } catch (Exception e) {
            Map<String, Object> out = Map.of(
                    "enabled", true,
                    "available", false,
                    "issuer", issuerUri,
                    "message", "unreachable"
            );
            cached.set(out);
            nextProbeAt.set(now.plus(Duration.ofMillis(failBackoffMs)));
            return out;
        }
    }
}

