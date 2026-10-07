package com.kk.security.gateway;

import java.util.List;
import java.util.Optional;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * 网关 access token 本地验签：RS256 签名（JWKS，Nimbus 内置缓存与按 kid 刷新）、iss、exp/nbf（默认 60s 时钟偏差）、
 * aud 含本应用 client_id、token_use=access（拒绝 id_token 冒充），以及踢下线吊销检查。
 */
@Component
public class GatewayTokenVerifier {

    private final GatewayAuthProperties props;
    private final GatewayRevocationStore revocationStore;
    private volatile JwtDecoder decoder;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayTokenVerifier(GatewayAuthProperties props, GatewayRevocationStore revocationStore) {
        this.props = props;
        this.revocationStore = revocationStore;
    }

    /** 测试用：注入已配置好校验器的解码器。 */
    GatewayTokenVerifier(GatewayAuthProperties props, GatewayRevocationStore revocationStore, JwtDecoder decoder) {
        this(props, revocationStore);
        this.decoder = decoder;
    }

    /** 已验证的网关身份。guid 一律按字符串处理。 */
    public record GatewayIdentity(String guid, String username, String sid) {
    }

    /** 校验失败（签名/过期/aud/类型/已吊销）返回 empty。 */
    public Optional<GatewayIdentity> verify(String token) {
        Jwt jwt;
        try {
            jwt = decoder().decode(token);
        } catch (JwtException e) {
            return Optional.empty();
        }
        GatewayIdentity identity = new GatewayIdentity(jwt.getSubject(), jwt.getClaimAsString("username"),
                jwt.getClaimAsString("sid"));
        if (identity.guid() == null || identity.guid().isBlank()
                || revocationStore.isRevoked(identity.guid(), identity.sid(), jwt.getIssuedAt())) {
            return Optional.empty();
        }
        return Optional.of(identity);
    }

    private JwtDecoder decoder() {
        JwtDecoder d = decoder;
        if (d == null) {
            synchronized (this) {
                if (decoder == null) {
                    decoder = build(NimbusJwtDecoder.withJwkSetUri(props.resolvedJwkSetUri()).build(), props);
                }
                d = decoder;
            }
        }
        return d;
    }

    static NimbusJwtDecoder build(NimbusJwtDecoder nimbus, GatewayAuthProperties props) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(props.getClientId())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "aud 不匹配", null));
        };
        OAuth2TokenValidator<Jwt> accessOnly = jwt -> "access".equals(jwt.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "不是 access token", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.getIssuer()), audience, accessOnly));
        return nimbus;
    }
}
