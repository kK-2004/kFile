package com.kk.security.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 网关踢下线回调的本地吊销表（内存）：
 * <ul>
 *   <li>LOGOUT：吊销该 sid 的全部 access token；</li>
 *   <li>FORCE_OFFLINE / BAN：吊销该用户在回调时刻之前签发的全部 access token。</li>
 * </ul>
 * access token 最长 15 分钟有效，记录保留 {@link #RETENTION} 后清理；网关同时吊销 refresh，过期后无法续期。
 * 回调 nonce 也在此去重。重启丢失记录的窗口内，被踢用户的旧 access token 最多再用到自然过期。
 */
@Component
public class GatewayRevocationStore {

    static final Duration RETENTION = Duration.ofHours(1);

    private final Map<String, Instant> revokedSids = new ConcurrentHashMap<>();
    /** guid -> 在此时刻之前签发的 token 无效。 */
    private final Map<String, Instant> revokedBefore = new ConcurrentHashMap<>();
    private final Map<String, Instant> seenNonces = new ConcurrentHashMap<>();

    public void revokeSid(String sid) {
        if (sid != null && !sid.isBlank()) {
            revokedSids.put(sid, Instant.now());
        }
        purge();
    }

    public void revokeUser(String guid) {
        revokedBefore.put(guid, Instant.now());
        purge();
    }

    public boolean isRevoked(String guid, String sid, Instant issuedAt) {
        if (sid != null && revokedSids.containsKey(sid)) {
            return true;
        }
        Instant before = revokedBefore.get(guid);
        // iat 精度为秒：同一秒内签发的也视为吊销（宁严勿松）
        return before != null && (issuedAt == null || !issuedAt.isAfter(before));
    }

    /** 首次出现返回 true；重复 nonce 返回 false。 */
    public boolean markNonce(String nonce) {
        purge();
        return seenNonces.putIfAbsent(nonce, Instant.now()) == null;
    }

    private void purge() {
        Instant cutoff = Instant.now().minus(RETENTION);
        revokedSids.values().removeIf(t -> t.isBefore(cutoff));
        revokedBefore.values().removeIf(t -> t.isBefore(cutoff));
        seenNonces.values().removeIf(t -> t.isBefore(cutoff));
    }
}
