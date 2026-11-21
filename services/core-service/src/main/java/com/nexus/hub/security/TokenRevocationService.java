package com.nexus.hub.security;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/**
 * Redis-backed JWT blacklist. FAIL-CLOSED: if Redis cannot be reached, both the revocation check
 * and the revocation itself throw {@link TokenRevocationUnavailableException} (mapped to HTTP 503)
 * instead of silently treating a token as valid or a logout as successful.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private static final String REVOCATION_PREFIX = "jwt:blacklist:";
    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;

    public void revokeToken(String token) {
        Date expiration;
        try {
            expiration = jwtService.extractExpiration(token);
        } catch (JwtException | IllegalArgumentException e) {
            // Invalid/expired token: nothing usable to revoke.
            log.warn("Skipping revocation of an invalid or expired token: {}", e.getMessage());
            return;
        }

        long remainingMillis = expiration.getTime() - Instant.now().toEpochMilli();
        if (remainingMillis <= 0) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(
                    REVOCATION_PREFIX + hashToken(token),
                    "revoked",
                    Duration.ofMillis(remainingMillis)
            );
            log.info("Token revoked successfully until {}", expiration);
        } catch (Exception e) {
            log.error("Token revocation store unavailable while revoking token: {}", e.getMessage());
            throw new TokenRevocationUnavailableException("Token revocation service is temporarily unavailable", e);
        }
    }

    /**
     * @return true if the token has been revoked
     * @throws TokenRevocationUnavailableException if the revocation store cannot be queried
     */
    public boolean isTokenRevoked(String token) {
        try {
            Boolean exists = redisTemplate.hasKey(REVOCATION_PREFIX + hashToken(token));
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("Error checking token revocation in Redis (failing closed): {}", e.getMessage());
            throw new TokenRevocationUnavailableException("Token revocation service is temporarily unavailable", e);
        }
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
