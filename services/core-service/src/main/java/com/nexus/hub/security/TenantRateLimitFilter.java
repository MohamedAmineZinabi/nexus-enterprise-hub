package com.nexus.hub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.common.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Distributed Redis-backed fixed-window rate limiter scoped per Tenant ID.
 * Protects compute-intensive AI endpoints (/api/v1/tasks, /api/v1/documents/upload)
 * using atomic INCR + EXPIRE semantics.
 */
@Slf4j
@Component
public class TenantRateLimitFilter extends OncePerRequestFilter {

    private static final String HEADER_RATE_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_RATE_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long requestsPerMinute;
    private final long windowSeconds;

    @Autowired
    public TenantRateLimitFilter(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${nexus.rate-limit.enabled:true}") boolean enabled,
            @Value("${nexus.rate-limit.requests-per-minute:30}") long requestsPerMinute,
            @Value("${nexus.rate-limit.window-seconds:60}") long windowSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = extractNormalizedPath(request);
        String bucket = resolveRateLimitBucket(path);
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID tenantId = principal.getTenantId();
        if (tenantId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String redisKey = String.format("rate_limit:tenant:%s:%s", tenantId, bucket);
        Long currentCount;
        try {
            currentCount = stringRedisTemplate.opsForValue().increment(redisKey);
            if (currentCount != null && currentCount == 1L) {
                stringRedisTemplate.expire(redisKey, windowSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception ex) {
            // Fail-open gracefully if Redis experiences transient disruption
            log.warn("Redis rate limiter error for tenant={}, key={}: {}. Failing open.", tenantId, redisKey, ex.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        long count = currentCount != null ? currentCount : 1L;
        long remaining = Math.max(0L, requestsPerMinute - count);

        response.setHeader(HEADER_RATE_LIMIT, String.valueOf(requestsPerMinute));
        response.setHeader(HEADER_RATE_REMAINING, String.valueOf(remaining));

        if (count > requestsPerMinute) {
            long retryAfter = resolveRetryAfterSeconds(redisKey);
            response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfter));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            log.warn("Tenant rate limit exceeded: tenantId={}, endpoint={}, count={}/{} (window={}s)",
                    tenantId, path, count, requestsPerMinute, windowSeconds);

            ApiResponse<Void> errorPayload = ApiResponse.error(
                    String.format("Rate limit exceeded for tenant. Maximum %d requests per %d seconds allowed.",
                            requestsPerMinute, windowSeconds),
                    Map.of(
                            "code", "RATE_LIMIT_EXCEEDED",
                            "tenantId", tenantId.toString(),
                            "endpoint", path,
                            "limit", requestsPerMinute,
                            "remaining", 0,
                            "retryAfterSeconds", retryAfter
                    )
            );
            objectMapper.writeValue(response.getWriter(), errorPayload);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveRateLimitBucket(String path) {
        if ("/api/v1/tasks".equals(path)) {
            return "tasks";
        }
        if ("/api/v1/documents/upload".equals(path)) {
            return "documents_upload";
        }
        return null;
    }

    private String extractNormalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private long resolveRetryAfterSeconds(String redisKey) {
        try {
            Long ttl = stringRedisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                return ttl;
            }
            if (ttl == null || ttl == -1L) {
                stringRedisTemplate.expire(redisKey, windowSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
        return windowSeconds;
    }
}
