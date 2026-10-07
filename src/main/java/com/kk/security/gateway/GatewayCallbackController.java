package com.kk.security.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关踢下线回调（网关 docs/kick-callback.md）：验签 → 时间戳 ±5 分钟 → nonce 去重 → 按事件吊销。
 * 网关投递 at-least-once，处理幂等。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class GatewayCallbackController {

    static final long MAX_SKEW_SECONDS = 300;

    private final GatewayAuthProperties props;
    private final GatewayRevocationStore revocationStore;
    private final ObjectMapper objectMapper;

    @PostMapping("/api/gateway/callback")
    public ResponseEntity<?> callback(@RequestBody byte[] body,
                                      @RequestHeader(value = "X-Gateway-Signature", required = false) String signature) {
        String secret = props.getCallbackSecret();
        if (!props.isEnabled() || secret == null || secret.isBlank()) {
            return ResponseEntity.status(404).build();
        }
        if (signature == null || !MessageDigest.isEqual(
                sign(secret, body).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("message", "签名无效"));
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "报文无效"));
        }
        long timestamp = event.path("timestamp").asLong(0);
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > MAX_SKEW_SECONDS) {
            return ResponseEntity.status(401).body(Map.of("message", "时间戳过期"));
        }
        String nonce = event.path("nonce").asText("");
        if (nonce.isBlank() || !revocationStore.markNonce(nonce)) {
            // 重复投递：已处理过，按成功返回
            return ResponseEntity.ok(Map.of("ok", true));
        }
        String type = event.path("event").asText("");
        String guid = event.path("userId").asText("");
        switch (type) {
            case "LOGOUT" -> revocationStore.revokeSid(event.path("sid").asText(""));
            case "FORCE_OFFLINE", "BAN" -> {
                if (!guid.isBlank()) {
                    revocationStore.revokeUser(guid);
                }
            }
            default -> log.warn("未知网关回调事件: {}", type);
        }
        log.info("网关回调 {} guid={} reason={}", type, guid, event.path("reason").asText(""));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
