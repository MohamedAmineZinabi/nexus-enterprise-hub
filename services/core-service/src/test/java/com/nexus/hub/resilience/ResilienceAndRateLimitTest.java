package com.nexus.hub.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.grpc.ai.v1.RagServiceGrpc;
import com.nexus.hub.grpc.client.GrpcContextHelper;
import com.nexus.hub.grpc.client.RagGrpcClient;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResilienceAndRateLimitTest {

    // --- Circuit Breaker & Retry Components ---
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private RetryRegistry retryRegistry;

    // --- Rate Limiting Components ---
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

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
        rateLimitFilter = new TenantRateLimitFilter(
                stringRedisTemplate,
                objectMapper,
                true,  // enabled
                30L,   // 30 req/min
                60L    // 60s window
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

        // Simulate failing gRPC supplier throwing UNAVAILABLE StatusRuntimeException
        Supplier<String> failingSupplier = () -> {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("AI worker unreachable"));
        };
        Supplier<String> decoratedCall = CircuitBreaker.decorateSupplier(circuitBreaker, failingSupplier);

        // Execute 5 calls (minimumNumberOfCalls = 5, all failing -> failure rate 100% >= 50%)
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(decoratedCall::get)
                    .isInstanceOf(StatusRuntimeException.class)
                    .hasMessageContaining("AI worker unreachable");
        }

        // CircuitBreaker must now transition to OPEN
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // The 6th call must immediately fast-fail with CallNotPermittedException without invoking supplier
        assertThatThrownBy(decoratedCall::get)
                .isInstanceOf(CallNotPermittedException.class)
                .hasMessageContaining("CircuitBreaker 'aiServiceGrpc' is OPEN");
    }

    @Test
    @DisplayName("CircuitBreaker fallback catches CallNotPermittedException and translates to AiServiceUnavailableException")
    void testCircuitBreakerFallbackTranslatesToAiServiceUnavailableException() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("aiServiceGrpc");

        // Force circuit breaker to OPEN state
        circuitBreaker.transitionToOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Simulate gRPC call wrapped in CircuitBreaker with fallback logic
        Supplier<String> decoratedWithFallback = () -> {
            try {
                return circuitBreaker.executeSupplier(() -> "never-reached");
            } catch (CallNotPermittedException ex) {
                // Mimics the fallback behavior in RagGrpcClient / AgentGrpcClient
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
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(5L); // 5th request out of 30

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        // Verify request was allowed through filter chain
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());

        // Verify RateLimit headers
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("25");
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    @DisplayName("RateLimiter sets 60s TTL on first request of window")
    void testRateLimiterSetsTtlOnFirstRequest() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L); // 1st request of window

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        // Verify expire was called on first request
        verify(stringRedisTemplate).expire(
                eq(String.format("rate_limit:tenant:%s:tasks", tenantId)),
                eq(60L),
                eq(TimeUnit.SECONDS)
        );
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("29");
    }

    @Test
    @DisplayName("RateLimiter blocks requests exceeding quota returning HTTP 429 Too Many Requests")
    void testRateLimiterBlocksRequestsExceedingQuota() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(31L); // 31st request exceeds 30
        when(stringRedisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(45L);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        // Filter chain must NOT have been called (request blocked)
        assertThat(filterChain.getRequest()).isNull();

        // HTTP 429 Too Many Requests
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getContentType()).isEqualTo("application/json");

        // Rate limit headers
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("Retry-After")).isEqualTo("45");

        // Verify JSON response body contains error details
        String responseBody = response.getContentAsString();
        assertThat(responseBody).contains("\"success\":false");
        assertThat(responseBody).contains("RATE_LIMIT_EXCEEDED");
        assertThat(responseBody).contains("Rate limit exceeded for tenant");
        assertThat(responseBody).contains("\"retryAfterSeconds\":45");
    }

    @Test
    @DisplayName("RateLimiter skips non-metered paths like /api/v1/auth/login")
    void testRateLimiterSkipsNonMeteredPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, filterChain);

        // No Redis interaction
        verifyNoInteractions(stringRedisTemplate);
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("RateLimiter fails open gracefully when Redis experiences transient connection failure")
    void testRateLimiterFailsOpenOnRedisFailure() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis master unreachable"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // Must not throw exception; fails open gracefully
        rateLimitFilter.doFilter(request, response, filterChain);

        // Request still forwarded to target service
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }
}
