package com.kk.security.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 踢下线回调：验签、时间戳、nonce 去重与按事件吊销。 */
class GatewayCallbackControllerTest {

    private static final String SECRET = "callback-secret";
    private GatewayRevocationStore store;
    private GatewayCallbackController controller;

    @BeforeEach
    void setUp() {
        GatewayAuthProperties props = new GatewayAuthProperties();
        props.setEnabled(true);
        props.setCallbackSecret(SECRET);
        store = new GatewayRevocationStore();
        controller = new GatewayCallbackController(props, store, new ObjectMapper());
    }

    private static byte[] body(String event, String nonce, long timestamp) {
        return ("{\"event\":\"" + event + "\",\"userId\":\"7351234567890123456\",\"username\":\"alice\","
                + "\"sid\":\"s1\",\"reason\":\"r\",\"timestamp\":" + timestamp + ",\"nonce\":\"" + nonce + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void forceOfflineRevokesUser() {
        byte[] b = body("FORCE_OFFLINE", "n1", Instant.now().getEpochSecond());
        var resp = controller.callback(b, GatewayCallbackController.sign(SECRET, b));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(store.isRevoked("7351234567890123456", "other", Instant.now().minusSeconds(5))).isTrue();
    }

    @Test
    void logoutRevokesSid() {
        byte[] b = body("LOGOUT", "n2", Instant.now().getEpochSecond());
        controller.callback(b, GatewayCallbackController.sign(SECRET, b));
        assertThat(store.isRevoked("x", "s1", Instant.now())).isTrue();
    }

    @Test
    void badSignatureOrStaleTimestampRejected() {
        byte[] b = body("BAN", "n3", Instant.now().getEpochSecond());
        assertThat(controller.callback(b, "sha256=deadbeef").getStatusCode().value()).isEqualTo(401);
        byte[] stale = body("BAN", "n4", Instant.now().getEpochSecond() - 600);
        assertThat(controller.callback(stale, GatewayCallbackController.sign(SECRET, stale)).getStatusCode().value())
                .isEqualTo(401);
        assertThat(store.isRevoked("7351234567890123456", "s9", Instant.now().minusSeconds(5))).isFalse();
    }

    @Test
    void duplicateNonceIsIdempotent() {
        byte[] b = body("BAN", "n5", Instant.now().getEpochSecond());
        String sig = GatewayCallbackController.sign(SECRET, b);
        assertThat(controller.callback(b, sig).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.callback(b, sig).getStatusCode().value()).isEqualTo(200);
    }
}
