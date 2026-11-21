package com.nexus.hub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.modules.auth.dto.RegisterRequest;
import com.nexus.hub.modules.auth.service.AuthService;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.tenant.repository.TenantRepository;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator authorization rules of the real SecurityConfig chain (the same chain Spring Boot applies to
 * the management child context on management.server.port): health is public, everything else under
 * /actuator requires ROLE_PLATFORM_OPERATOR — a tenant ADMIN is NOT enough.
 */
@SpringJUnitWebConfig(ActuatorSecurityTest.Config.class)
class ActuatorSecurityTest {

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Config {
        @Bean
        UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(UserRepository userRepository) {
            return new JwtAuthenticationFilter(Mockito.mock(JwtService.class), Mockito.mock(TokenRevocationService.class),
                    userRepository, new ObjectMapper());
        }

        @Bean
        TenantRateLimitFilter tenantRateLimitFilter() {
            return new TenantRateLimitFilter(Mockito.mock(StringRedisTemplate.class), new ObjectMapper(),
                    false, 30L, 60L, 10L, 60L);
        }

        @Bean
        FakeActuatorEndpoints fakeActuatorEndpoints() {
            return new FakeActuatorEndpoints();
        }
    }

    /** Stand-ins for actuator endpoints, so only the security rules are under test. */
    @RestController
    static class FakeActuatorEndpoints {
        @GetMapping("/actuator/health")
        String health() {
            return "{\"status\":\"UP\"}";
        }

        @GetMapping("/actuator/health/liveness")
        String liveness() {
            return "{\"status\":\"UP\"}";
        }

        @GetMapping("/actuator/metrics")
        String metrics() {
            return "{}";
        }

        @GetMapping("/actuator/env")
        String env() {
            return "{}";
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static RequestPostProcessor as(Role role) {
        Tenant tenant = Tenant.builder().id(UUID.randomUUID()).name("T").slug("t").status("ACTIVE").build();
        User user = User.builder().id(UUID.randomUUID()).tenant(tenant).email(role + "@t.io").fullName("U")
                .passwordHash("x").role(role).enabled(true).build();
        UserPrincipal principal = UserPrincipal.create(user);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("/actuator/health and /actuator/health/** are public")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Other actuator endpoints reject anonymous callers with 401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tenant ADMIN / ANALYST get 403 on non-health actuator endpoints")
    void tenantRolesForbidden() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(as(Role.ADMIN))).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env").with(as(Role.ADMIN))).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").with(as(Role.ANALYST))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PLATFORM_OPERATOR can read non-health actuator endpoints")
    void platformOperatorAllowed() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(as(Role.PLATFORM_OPERATOR))).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env").with(as(Role.PLATFORM_OPERATOR))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Registration assigns ADMIN (org owner) and offers no way to request a role")
    void registrationNeverAssignsPlatformOperator() {
        assertThat(Arrays.stream(RegisterRequest.class.getDeclaredFields()).map(f -> f.getName().toLowerCase()))
                .noneMatch(name -> name.contains("role"));

        UserRepository userRepository = mock(UserRepository.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        JwtService jwtService = mock(JwtService.class);
        AuthService authService = new AuthService(userRepository, tenantRepository, mock(PasswordEncoder.class),
                jwtService, mock(TokenRevocationService.class), mock(AuthenticationManager.class));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(UserPrincipal.class))).thenReturn("token");

        authService.register(RegisterRequest.builder().organizationName("Org").organizationSlug("org")
                .fullName("Owner").email("owner@org.io").password("password123").build());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
    }
}
