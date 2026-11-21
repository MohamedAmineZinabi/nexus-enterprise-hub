package com.nexus.hub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
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
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtFilterAndRevocationTest {

    private static final String TOKEN = "header.payload.signature";

    @Mock
    private JwtService jwtService;
    @Mock
    private TokenRevocationService tokenRevocationService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, tokenRevocationService, userRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stubValidUser() {
        Tenant tenant = Tenant.builder().id(UUID.randomUUID()).name("A").slug("a").status("ACTIVE").build();
        User user = User.builder().id(UUID.randomUUID()).tenant(tenant).email("a@a.io").fullName("A")
                .passwordHash("x").role(Role.ANALYST).enabled(true).build();
        when(tokenRevocationService.isTokenRevoked(TOKEN)).thenReturn(false);
        when(jwtService.isTokenValid(TOKEN)).thenReturn(true);
        when(jwtService.extractUsername(TOKEN)).thenReturn("a@a.io");
        when(userRepository.findByEmail("a@a.io")).thenReturn(Optional.of(user));
    }

    // ---------------------------------------------------------------- ?token= restriction

    @Test
    @DisplayName("?token= is accepted for GET /api/v1/tasks/{id}/stream")
    void queryTokenAcceptedOnSseStream() throws Exception {
        stubValidUser();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks/" + UUID.randomUUID() + "/stream");
        request.setParameter("token", TOKEN);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("?token= is ignored on every other endpoint and on non-GET stream requests")
    void queryTokenIgnoredElsewhere() throws Exception {
        String[][] cases = {
                {"GET", "/api/v1/tasks"},
                {"GET", "/api/v1/tasks/" + UUID.randomUUID()},
                {"GET", "/api/v1/documents"},
                {"POST", "/api/v1/tasks/" + UUID.randomUUID() + "/stream"},
                {"GET", "/api/v1/tasks/" + UUID.randomUUID() + "/stream/extra"},
        };
        for (String[] c : cases) {
            MockHttpServletRequest request = new MockHttpServletRequest(c[0], c[1]);
            request.setParameter("token", TOKEN);
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(c[0] + " " + c[1]).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).as(c[0] + " " + c[1]).isNull();
        }
        verifyNoInteractions(jwtService, tokenRevocationService, userRepository);
    }

    @Test
    @DisplayName("Authorization: Bearer header is honoured on regular endpoints")
    void bearerHeaderAccepted() throws Exception {
        stubValidUser();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer " + TOKEN);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    // ---------------------------------------------------------------- fail-closed revocation

    @Test
    @DisplayName("Filter responds 503 (and does not authenticate) when the revocation store is down")
    void filterFailsClosedWhenRedisDown() throws Exception {
        when(tokenRevocationService.isTokenRevoked(TOKEN))
                .thenThrow(new TokenRevocationUnavailableException("down", null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getContentAsString()).contains("\"success\":false").contains("TOKEN_REVOCATION_UNAVAILABLE");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtService, never()).isTokenValid(anyString());
    }

    @Test
    @DisplayName("isTokenRevoked throws (fail-closed) instead of returning false on Redis errors")
    void revocationCheckFailsClosed() {
        TokenRevocationService service = new TokenRevocationService(redisTemplate, jwtService);
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.isTokenRevoked(TOKEN))
                .isInstanceOf(TokenRevocationUnavailableException.class)
                .satisfies(ex -> assertThat(((TokenRevocationUnavailableException) ex).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("Logout (revokeToken) fails with 503 when Redis is down instead of silently succeeding")
    void revokeFailsClosed() {
        TokenRevocationService service = new TokenRevocationService(redisTemplate, jwtService);
        when(jwtService.extractExpiration(TOKEN)).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service.revokeToken(TOKEN))
                .isInstanceOf(TokenRevocationUnavailableException.class);
    }

    @Test
    @DisplayName("Revoking an invalid/expired token is a no-op, not an error")
    void revokeInvalidTokenIsNoop() {
        TokenRevocationService service = new TokenRevocationService(redisTemplate, jwtService);
        when(jwtService.extractExpiration(TOKEN)).thenThrow(new ExpiredJwtException(null, null, "expired"));

        assertThatCode(() -> service.revokeToken(TOKEN)).doesNotThrowAnyException();
        verifyNoInteractions(redisTemplate);
    }
}
