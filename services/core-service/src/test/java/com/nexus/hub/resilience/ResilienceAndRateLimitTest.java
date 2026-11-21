package com.nexus.hub.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.grpc.ai.v1.RagServiceGrpc;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.security.TenantRateLimitFilter;
import com.nexus.hub.security.UserPrincipal;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ResilienceAndRateLimitTest {

    // --- Circuit Breaker & Retry Components ---
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private RetryRegistry retryRegistry;

    // --- Rate Limiting Components ---
    // Hand-written fake: the filter now uses a single atomic Lua script (INCR + EXPIRE + TTL)
    private FakeScriptRedisTemplate stringRedisTemplate;

    @Mock
    private RagServiceGrpc.RagServiceBlockingStub ragStub;

    private ObjectMapper objectMapper;
    private TenantRateLimitFilter rateLimitFilter;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // 1. Initialize Resilience4j CircuitBreaker with production parameters
        // slidingWindowSize: 10, minimumNumberOfCalls: 5, failureRateThreshold: 50%
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(StatusRuntimeException.class, AiServiceUnavailableException.class)
                .build();
        circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);

        // 2. Initialize Resilience4j Retry with production parameters
        // maxAttempts: 2, waitDuration: 500ms
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(100))
                .retryExceptions(AiServiceUnavailableException.class)
                .build();
        retryRegistry = RetryRegistry.of(retryConfig);

        // 3. Initialize Rate Limiter Filter
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        stringRedisTemplate = new FakeScriptRedisTemplate();
        rateLimitFilter = new TenantRateLimitFilter(
                stringRedisTemplate,
                objectMapper,
                true,  // enabled
                30L,   // 30 req/min per tenant
                60L,   // 60s window
                10L,   // 10 auth attempts per client IP
                60L    // 60s auth window
        );

        // 4. Setup Security Context
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();

        UserPrincipal principal = UserPrincipal.builder()
                .id(userId)
                .tenantId(tenantId)
                .email("resilience.tester@techcorp.io")
                .role(Role.ADMIN)
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .enabled(true)
                .build();

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // Part 1: Circuit Breaker & Retry Tests
    // =========================================================================

    @Test
    @DisplayName("CircuitBreaker transitions from CLOSED to OPEN after minimum failed calls reach failure threshold")
    void testCircuitBreakerTransitionsFromClosedToOpen() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("aiServiceGrpc");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        Supplier<String> failingSupplier = () -> {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("AI worker unreachable"));
        };
        Supplier<String> decoratedCall = CircuitBreaker.decorateSupplier(circuitBreaker, failingSupplier);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(decoratedCall::get)
                    .isInstanceOf(StatusRuntimeException.class)
                    .hasMessageContaining("AI worker unreachable");
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(decoratedCall::get)
                .isInstanceOf(CallNotPermittedException.class)
                .hasMessageContaining("CircuitBreaker 'aiServiceGrpc' is OPEN");
    }

    @Test
    @DisplayName("CircuitBreaker fallback catches CallNotPermittedException and translates to AiServiceUnavailableException")
    void testCircuitBreakerFallbackTranslatesToAiServiceUnavailableException() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("aiServiceGrpc");

        circuitBreaker.transitionToOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Supplier<String> decoratedWithFallback = () -> {
            try {
                return circuitBreaker.executeSupplier(() -> "never-reached");
            } catch (CallNotPermittedException ex) {
                throw new AiServiceUnavailableException(
                        "AI Service is temporarily unavailable (circuit breaker OPEN): " + ex.getMessage());
            }
        };

        assertThatThrownBy(decoratedWithFallback::get)
                .isInstanceOf(AiServiceUnavailableException.class)
                .hasMessageContaining("circuit breaker OPEN")
                .hasMessageContaining("aiServiceGrpc");
    }

    @Test
    @DisplayName("Resilience4j Retry retries transient AiServiceUnavailableException and succeeds on 2nd attempt")
    void testRetryRetriesTransientExceptionAndSucceeds() {
        Retry retry = retryRegistry.retry("aiServiceRetry");
        AtomicInteger attemptCounter = new AtomicInteger(0);

        Supplier<String> flakySupplier = () -> {
            int attempt = attemptCounter.incrementAndGet();
            if (attempt == 1) {
                throw new AiServiceUnavailableException("Transient gRPC connection failure");
            }
            return "SUCCESS_ON_ATTEMPT_2";
        };

        Supplier<String> decorated = Retry.decorateSupplier(retry, flakySupplier);
        String result = decorated.get();

        assertThat(result).isEqualTo("SUCCESS_ON_ATTEMPT_2");
        assertThat(attemptCounter.get()).isEqualTo(2);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Resilience4j Retry exhausts max attempts (2) and rethrows AiServiceUnavailableException")
    void testRetryExhaustsMaxAttempts() {
        Retry retry = retryRegistry.retry("aiServiceRetry");
        AtomicInteger attemptCounter = new AtomicInteger(0);

        Supplier<String> permanentlyFailingSupplier = () -> {
            attemptCounter.incrementAndGet();
            throw new AiServiceUnavailableException("Permanent outage");
        };

        Supplier<String> decorated = Retry.decorateSupplier(retry, permanentlyFailingSupplier);

        assertThatThrownBy(decorated::get)
                .isInstanceOf(AiServiceUnavailableException.class)
                .hasMessageContaining("Permanent outage");

        assertThat(attemptCounter.get()).isEqualTo(2);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    // =========================================================================
    // Part 2: Tenant Distributed Rate Limiter Tests
    // =========================================================================

    @Test
    @DisplayName("RateLimiter allows requests under quota and sets rate limit response headers")
    void testRateLimiterAllowsRequestsUnderQuota() throws Exception {
        stringRedisTemplate.nextResult = List.of(5L, 55L); // 5th request out of 30

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("25");
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    @DisplayName("RateLimiter counts atomically: one Lua script doing INCR + EXPIRE with the window TTL")
    void testRateLimiterUsesAtomicIncrExpireScript() throws Exception {
        stringRedisTemplate.nextResult = List.of(1L, 60L); // 1st request of window

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        assertThat(stringRedisTemplate.calls).hasSize(1);
        ScriptCall call = stringRedisTemplate.calls.get(0);
        assertThat(call.keys()).containsExactly(String.format("rate_limit:tenant:%s:tasks", tenantId));
        assertThat(call.args()).containsExactly("60");
        assertThat(call.scriptText()).contains("INCR").contains("EXPIRE");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("29");
    }

    @Test
    @DisplayName("RateLimiter blocks requests exceeding quota returning HTTP 429 Too Many Requests")
    void testRateLimiterBlocksRequestsExceedingQuota() throws Exception {
        stringRedisTemplate.nextResult = List.of(31L, 45L); // 31st request exceeds 30

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("Retry-After")).isEqualTo("45");

        String responseBody = response.getContentAsString();
        assertThat(responseBody).contains("\"success\":false");
        assertThat(responseBody).contains("RATE_LIMIT_EXCEEDED");
        assertThat(responseBody).contains("Rate limit exceeded for tenant");
        assertThat(responseBody).contains("\"retryAfterSeconds\":45");
    }

    @Test
    @DisplayName("GET list/polling/stream requests on metered paths do NOT consume tenant quota")
    void testRateLimiterExemptsGetRequests() throws Exception {
        for (String path : List.of("/api/v1/tasks", "/api/v1/tasks/" + UUID.randomUUID(), "/api/v1/documents")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            rateLimitFilter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
            assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
        }
        assertThat(stringRedisTemplate.calls).isEmpty();
    }

    @Test
    @DisplayName("RateLimiter skips non-metered POST paths like /api/v1/auth/logout")
    void testRateLimiterSkipsNonMeteredPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        assertThat(stringRedisTemplate.calls).isEmpty();
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Auth login is throttled per client IP (first X-Forwarded-For entry) even without a principal")
    void testAuthLoginIpThrottle() throws Exception {
        SecurityContextHolder.clearContext();

        // Under the limit: allowed, keyed by client IP
        stringRedisTemplate.nextResult = List.of(3L, 50L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.setRemoteAddr("172.18.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(stringRedisTemplate.calls.get(0).keys()).containsExactly("rate_limit:ip:203.0.113.7:auth_login");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");

        // Over the limit: 429 + Retry-After
        stringRedisTemplate.nextResult = List.of(11L, 42L);
        MockHttpServletRequest blocked = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        blocked.addHeader("X-Real-IP", "198.51.100.9");
        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();

        rateLimitFilter.doFilter(blocked, blockedResponse, blockedChain);

        assertThat(blockedChain.getRequest()).isNull();
        assertThat(blockedResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(blockedResponse.getHeader("Retry-After")).isEqualTo("42");
        assertThat(blockedResponse.getContentAsString()).contains("RATE_LIMIT_EXCEEDED").contains("\"success\":false");
        assertThat(stringRedisTemplate.calls.get(1).keys()).containsExactly("rate_limit:ip:198.51.100.9:auth_register");
    }

    @Test
    @DisplayName("Auth throttle falls back to remoteAddr when no proxy headers are present")
    void testAuthThrottleFallsBackToRemoteAddr() throws Exception {
        SecurityContextHolder.clearContext();
        stringRedisTemplate.nextResult = List.of(1L, 60L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.33");

        rateLimitFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(stringRedisTemplate.calls.get(0).keys()).containsExactly("rate_limit:ip:192.0.2.33:auth_login");
    }

    @Test
    @DisplayName("RateLimiter fails open gracefully when Redis experiences transient connection failure")
    void testRateLimiterFailsOpenOnRedisFailure() throws Exception {
        stringRedisTemplate.failure = new RedisConnectionFailureException("Redis master unreachable");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // -------------------------------------------------------------------------
    // Test double
    // -------------------------------------------------------------------------
    record ScriptCall(String scriptText, List<String> keys, List<Object> args) {
    }

    static class FakeScriptRedisTemplate extends StringRedisTemplate {
        final List<ScriptCall> calls = new ArrayList<>();
        volatile List<Object> nextResult = List.of(1L, 60L);
        volatile RuntimeException failure;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            if (failure != null) {
                throw failure;
            }
            calls.add(new ScriptCall(script.getScriptAsString(), List.copyOf(keys), List.of(args)));
            return (T) nextResult;
        }
    }
}
