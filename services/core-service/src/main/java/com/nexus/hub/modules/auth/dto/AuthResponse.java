package com.nexus.hub.modules.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    @Builder.Default
    private String tokenType = "Bearer";
    private UUID userId;
    private UUID tenantId;
    private String email;
    private String fullName;
    private String role;
    private String organizationName;
    private long expiresInMs;
}
