package com.nexus.hub.modules.auth.service;

import com.nexus.hub.common.exception.BadRequestException;
import com.nexus.hub.common.exception.ResourceNotFoundException;
import com.nexus.hub.modules.auth.dto.AuthResponse;
import com.nexus.hub.modules.auth.dto.LoginRequest;
import com.nexus.hub.modules.auth.dto.RegisterRequest;
import com.nexus.hub.modules.auth.dto.UserProfileResponse;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.tenant.repository.TenantRepository;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.JwtService;
import com.nexus.hub.security.TokenRevocationService;
import com.nexus.hub.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;
    private final AuthenticationManager authenticationManager;

    @Value("${nexus.security.jwt.expiration-ms:86400000}")
    private long expirationMs;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email is already registered: " + request.getEmail());
        }

        String normalizedSlug = request.getOrganizationSlug().toLowerCase().trim().replaceAll("[^a-z0-9-]", "-");
        if (tenantRepository.existsBySlug(normalizedSlug)) {
            throw new BadRequestException("Organization slug is already in use: " + normalizedSlug);
        }

        Tenant tenant = Tenant.builder()
                .name(request.getOrganizationName())
                .slug(normalizedSlug)
                .status("ACTIVE")
                .build();
        tenant = tenantRepository.save(tenant);

        User user = User.builder()
                .tenant(tenant)
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(Role.ADMIN)
                .enabled(true)
                .build();
        user = userRepository.save(user);

        log.info("Registered new organization '{}' and admin user '{}'", tenant.getName(), user.getEmail());

        UserPrincipal principal = UserPrincipal.create(user);
        String token = jwtService.generateToken(principal);

        return buildAuthResponse(user, tenant, token);
    }

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail().toLowerCase().trim(),
                        request.getPassword()
                )
        );

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.getUsername()));

        String token = jwtService.generateToken(principal);
        log.info("User logged in successfully: {}", user.getEmail());

        return buildAuthResponse(user, user.getTenant(), token);
    }

    public void logout(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            tokenRevocationService.revokeToken(token);
            log.info("User successfully logged out and token blacklisted");
        }
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UserPrincipal principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.getUsername()));

        Tenant tenant = user.getTenant();

        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .enabled(user.isEnabled())
                .createdAt(user.getCreatedAt())
                .tenant(UserProfileResponse.TenantProfile.builder()
                        .id(tenant.getId())
                        .name(tenant.getName())
                        .slug(tenant.getSlug())
                        .status(tenant.getStatus())
                        .build())
                .build();
    }

    private AuthResponse buildAuthResponse(User user, Tenant tenant, String token) {
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .tenantId(tenant.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .organizationName(tenant.getName())
                .expiresInMs(expirationMs)
                .build();
    }
}
