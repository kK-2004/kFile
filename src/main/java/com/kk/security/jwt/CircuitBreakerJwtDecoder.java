package com.kk.security.jwt;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazily initializes a Nimbus issuer-based JwtDecoder and "circuit-breaks" initialization
 * when the issuer (discovery/JWKs) is temporarily unavailable.
 *
 * Goal: avoid failing application startup when the upstream SSO site is down.
 */
public final class CircuitBreakerJwtDecoder implements JwtDecoder {
    private final String issuerUri;
    private final OAuth2TokenValidator<Jwt> jwtValidator;
    private final Duration retryBackoff;

    private final AtomicReference<JwtDecoder> delegate = new AtomicReference<>();
    private final AtomicReference<Instant> nextInitAttemptAt = new AtomicReference<>(Instant.EPOCH);
    private final Object initLock = new Object();

    private volatile Throwable lastInitError;

    public CircuitBreakerJwtDecoder(String issuerUri,
                                    OAuth2TokenValidator<Jwt> jwtValidator,
                                    Duration retryBackoff) {
        this.issuerUri = Objects.requireNonNull(issuerUri, "issuerUri");
        this.jwtValidator = jwtValidator;
        this.retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        JwtDecoder current = delegate.get();
        if (current != null) {
            try {
                return current.decode(token);
            } catch (JwtException e) {
                if (looksLikeUpstreamUnavailable(e)) {
                    lastInitError = e;
                    delegate.set(null);
                    nextInitAttemptAt.set(Instant.now().plus(retryBackoff));
                }
                throw e;
            }
        }

        Instant now = Instant.now();
        Instant next = nextInitAttemptAt.get();
        if (now.isBefore(next)) {
            throw new JwtException("SSO upstream unavailable (circuit open)", lastInitError);
        }

        synchronized (initLock) {
            JwtDecoder afterLock = delegate.get();
            if (afterLock != null) {
                return afterLock.decode(token);
            }
            now = Instant.now();
            next = nextInitAttemptAt.get();
            if (now.isBefore(next)) {
                throw new JwtException("SSO upstream unavailable (circuit open)", lastInitError);
            }
            try {
                NimbusJwtDecoder nimbus = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
                if (jwtValidator != null) nimbus.setJwtValidator(jwtValidator);
                delegate.set(nimbus);
                lastInitError = null;
                return nimbus.decode(token);
            } catch (Exception e) {
                lastInitError = e;
                nextInitAttemptAt.set(Instant.now().plus(retryBackoff));
                throw new JwtException("SSO upstream unavailable (init failed)", e);
            }
        }
    }

    private static boolean looksLikeUpstreamUnavailable(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String name = cur.getClass().getName();
            if (cur instanceof java.net.ConnectException) return true;
            if (cur instanceof java.net.UnknownHostException) return true;
            if (cur instanceof java.net.SocketTimeoutException) return true;
            if ("org.springframework.web.client.ResourceAccessException".equals(name)) return true;
            if ("com.nimbusds.jose.RemoteKeySourceException".equals(name)) return true;
        }
        return false;
    }
}

