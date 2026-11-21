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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Distributed Redis-backed fixed-window rate limiter.
 * <ul>
 *   <li>Per-tenant quota: only {@code POST /api/v1/tasks} and {@code POST /api/v1/documents/upload}
 *       (compute-intensive AI work). GET list/polling/stream requests never consume quota.</li>
 *   <li>Per-client-IP throttle: {@code POST /api/v1/auth/login} and {@code POST /api/v1/auth/register}
 *       (unauthenticated, so keyed by client IP rather than principal).</li>
 * </ul>
 * Counting is atomic: INCR and EXPIRE run in a single Lua script, so a crash between the two can
 * never leave a counter without a TTL.
 * <p>
 * Availability trade-off: rate limiting FAILS OPEN. If Redis is unreachable the request is allowed
 * and a WARN is logged — a Redis outage must not take the whole API down. (Token revocation, by
 * contrast, fails closed; see TokenRevocationService.)
 */
@Slf4j
@Component
public class TenantRateLimitFilter extends OncePerRequestFilter {

    private static final String HEADER_RATE_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_RATE_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    /**
     * KEYS[1] = counter key, ARGV[1] = window seconds. Returns {count, ttlSeconds}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final RedisScript<List> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end "
                    + "local t = redis.call('TTL', KEYS[1]) "
                    + "if t < 0 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) t = tonumber(ARGV[1]) end "
                    + "return {c, t}",
            List.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long requestsPerMinute;
    private final long windowSeconds;
    private final long authRequestsPerWindow;
    private final long authWindowSeconds;

    @Autowired
    public TenantRateLimitFilter(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${nexus.rate-limit.enabled:true}") boolean enabled,
            @Value("${nexus.rate-limit.requests-per-minute:30}") long requestsPerMinute,
            @Value("${nexus.rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${nexus.rate-limit.auth-requests-per-minute:10}") long authRequestsPerWindow,
            @Value("${nexus.rate-limit.auth-window-seconds:60}") long authWindowSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.windowSeconds = windowSeconds;
        this.authRequestsPerWindow = authRequestsPerWindow;
        this.authWindowSeconds = authWindowSeconds;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = extractNormalizedPath(request);

        // 1. Unauthenticated auth endpoints: per-client-IP throttle (runs without a principal)
        String authBucket = resolveAuthBucket(path);
        if (authBucket != null) {
            String clientIp = resolveClientIp(request);
            String redisKey = String.format("rate_limit:ip:%s:%s", clientIp, authBucket);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", "RATE_LIMIT_EXCEEDED");
            details.put("endpoint", path);
            boolean allowed = applyLimit(redisKey, authRequestsPerWindow, authWindowSeconds, response, details,
                    String.format("Too many authentication attempts. Maximum %d requests per %d seconds allowed.",
                            authRequestsPerWindow, authWindowSeconds));
            if (allowed) {
                filterChain.doFilter(request, response);
            } else {
                log.warn("Auth endpoint throttled: ip={}, endpoint={}", clientIp, path);
            }
            return;
        }

        // 2. Metered AI endpoints: per-tenant quota (POST only)
        String bucket = resolveRateLimitBucket(path);
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            // Unauthenticated requests are rejected later by Spring Security.
            filterChain.doFilter(request, response);
            return;
        }

        UUID tenantId = principal.getTenantId();
        if (tenantId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String redisKey = String.format("rate_limit:tenant:%s:%s", tenantId, bucket);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", "RATE_LIMIT_EXCEEDED");
        details.put("tenantId", tenantId.toString());
        details.put("endpoint", path);
        boolean allowed = applyLimit(redisKey, requestsPerMinute, windowSeconds, response, details,
                String.format("Rate limit exceeded for tenant. Maximum %d requests per %d seconds allowed.",
                        requestsPerMinute, windowSeconds));
        if (allowed) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Tenant rate limit exceeded: tenantId={}, endpoint={}, limit={}/{}s",
                    tenantId, path, requestsPerMinute, windowSeconds);
        }
    }

    /**
     * Atomically counts the request and writes headers / 429 body.
     *
     * @return true if the request may proceed (including fail-open on Redis errors)
     */
    private boolean applyLimit(String redisKey, long limit, long window, HttpServletResponse response,
                               Map<String, Object> details, String message) throws IOException {
        long count;
        long ttl;
        try {
            List<?> result = stringRedisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(redisKey), String.valueOf(window));
            if (result == null || result.size() < 2) {
                log.warn("Unexpected rate limiter script result for key={}: {}. Failing open.", redisKey, result);
                return true;
            }
            count = toLong(result.get(0), 1L);
            ttl = toLong(result.get(1), window);
        } catch (Exception ex) {
            // Deliberate FAIL-OPEN: availability over strict quota enforcement during Redis disruption.
            log.warn("Redis rate limiter error for key={}: {}. Failing open.", redisKey, ex.getMessage());
            return true;
        }

        long remaining = Math.max(0L, limit - count);
        response.setHeader(HEADER_RATE_LIMIT, String.valueOf(limit));
        response.setHeader(HEADER_RATE_REMAINING, String.valueOf(remaining));

        if (count <= limit) {
            return true;
        }

        long retryAfter = ttl > 0 ? ttl : window;
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfter));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        details.put("limit", limit);
        details.put("remaining", 0);
        details.put("retryAfterSeconds", retryAfter);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(message, details));
        return false;
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
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

    private String resolveAuthBucket(String path) {
        if ("/api/v1/auth/login".equals(path)) {
            return "auth_login";
        }
        if ("/api/v1/auth/register".equals(path)) {
            return "auth_register";
        }
        return null;
    }

    /**
     * Client IP behind the NGINX gateway: first X-Forwarded-For entry, else X-Real-IP, else remoteAddr.
     * NOTE: these headers are only trustworthy when core-service is reachable exclusively through NGINX.
     */
    static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String first = forwardedFor.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return sanitizeIp(first);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return sanitizeIp(realIp.trim());
        }
        String remote = request.getRemoteAddr();
        return StringUtils.hasText(remote) ? sanitizeIp(remote) : "unknown";
    }

    /** Keeps Redis keys bounded and free of separators / control characters. */
    private static String sanitizeIp(String ip) {
        String cleaned = ip.replaceAll("[^0-9A-Fa-f:.]", "");
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        return cleaned.length() > 45 ? cleaned.substring(0, 45) : cleaned;
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
}
