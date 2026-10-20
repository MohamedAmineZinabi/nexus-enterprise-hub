package com.nexus.hub.security;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private static final String REVOCATION_PREFIX = "jwt:blacklist:";
    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;

    public void revokeToken(String token) {
        try {
            Date expiration = jwtService.extractExpiration(token);
            long remainingMillis = expiration.getTime() - Instant.now().toEpochMilli();

            if (remainingMillis > 0) {
                String tokenHash = hashToken(token);
                redisTemplate.opsForValue().set(
                        REVOCATION_PREFIX + tokenHash,
                        "revoked",
                        Duration.ofMillis(remainingMillis)
                );
                log.info("Token revoked successfully until {}", expiration);
            }
        } catch (Exception e) {
            log.warn("Failed to extract expiration for token revocation: {}", e.getMessage());
        }
    }

    public boolean isTokenRevoked(String token) {
        try {
            String tokenHash = hashToken(token);
            Boolean exists = redisTemplate.hasKey(REVOCATION_PREFIX + tokenHash);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("Error checking token revocation in Redis: {}", e.getMessage());
            return false;
        }
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(token.hashCode());
        }
    }
}
