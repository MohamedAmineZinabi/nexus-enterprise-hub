package com.nexus.hub.modules.auth.controller;

import com.nexus.hub.common.dto.ApiResponse;
import com.nexus.hub.modules.auth.dto.AuthResponse;
import com.nexus.hub.modules.auth.dto.LoginRequest;
import com.nexus.hub.modules.auth.dto.RegisterRequest;
import com.nexus.hub.modules.auth.dto.UserProfileResponse;
import com.nexus.hub.modules.auth.service.AuthService;
import com.nexus.hub.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Organization and user registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Authentication successful"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        authService.logout(authHeader);
        return ResponseEntity.ok(ApiResponse.success(null, "Session terminated and token revoked"));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getCurrentUser(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UserProfileResponse response = authService.getProfile(principal);
        return ResponseEntity.ok(ApiResponse.success(response, "User profile retrieved successfully"));
    }
}
