package com.kk.security.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** 网关 access token 本地验签：签名、iss、aud、exp、token_use 与踢下线吊销。 */
class GatewayTokenVerifierTest {

    private static final String ISSUER = "https://gw.ksite.xin";
    private RSAKey key;
    private GatewayRevocationStore store;
    private GatewayTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        GatewayAuthProperties props = new GatewayAuthProperties();
        props.setEnabled(true);
        props.setIssuer(ISSUER);
        props.setClientId("kfile");
        store = new GatewayRevocationStore();
        NimbusJwtDecoder decoder = GatewayTokenVerifier.build(
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                        .jwtProcessorCustomizer(GatewayTokenVerifier::acceptAccessTokenType).build(), props);
        verifier = new GatewayTokenVerifier(props, store, decoder);
    }

    private String token(Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        return token(key, customize);
    }

    private String token(RSAKey signingKey, Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        return token(signingKey, new JOSEObjectType("at+jwt"), customize);
    }

    /** 与网关 JwtService 一致：access token 头部 typ=at+jwt，id_token 为 JWT。 */
    private String token(RSAKey signingKey, JOSEObjectType typ, Consumer<JWTClaimsSet.Builder> customize)
            throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("7351234567890123456").audience("kfile")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(900)))
                .claim("sid", "s1").claim("username", "alice").claim("token_use", "access");
        customize.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(typ).keyID("k1").build(),
                claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void validAccessTokenYieldsGuidAsString() throws Exception {
        var identity = verifier.verify(token(c -> { })).orElseThrow();
        assertThat(identity.guid()).isEqualTo("7351234567890123456");
        assertThat(identity.username()).isEqualTo("alice");
        assertThat(identity.sid()).isEqualTo("s1");
    }

    @Test
    void rejectsOtherAudienceIssuerIdTokenAndExpired() throws Exception {
        assertThat(verifier.verify(token(c -> c.audience("other-app")))).isEmpty();
        assertThat(verifier.verify(token(c -> c.issuer("https://evil.example")))).isEmpty();
        assertThat(verifier.verify(token(c -> c.claim("token_use", "id")))).isEmpty();
        assertThat(verifier.verify(token(c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120)))))).isEmpty();
    }

    @Test
    void rejectsIdTokenHeaderType() throws Exception {
        assertThat(verifier.verify(token(key, JOSEObjectType.JWT, c -> { }))).isEmpty();
    }

    @Test
    void rejectsForeignSignatureAndGarbage() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("k1").generate();
        assertThat(verifier.verify(token(other, c -> { }))).isEmpty();
        assertThat(verifier.verify("not-a-jwt")).isEmpty();
    }

    @Test
    void kickedSessionAndUserAreRejected() throws Exception {
        String t = token(c -> { });
        store.revokeSid("s1");
        assertThat(verifier.verify(t)).isEmpty();

        GatewayRevocationStore fresh = new GatewayRevocationStore();
        fresh.revokeUser("7351234567890123456");
        assertThat(fresh.isRevoked("7351234567890123456", "s2", Instant.now().minusSeconds(5))).isTrue();
        // 踢下线之后重新登录签发的 token 不受影响
        assertThat(fresh.isRevoked("7351234567890123456", "s2", Instant.now().plusSeconds(2))).isFalse();
    }
}
