package com.nexus.hub.modules.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private UUID id;
    private String email;
    private String fullName;
    private String role;
    private boolean enabled;
    private TenantProfile tenant;
    private Instant createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantProfile {
        private UUID id;
        private String name;
        private String slug;
        private String status;
    }
}
