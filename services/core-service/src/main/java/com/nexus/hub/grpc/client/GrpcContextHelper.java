package com.nexus.hub.grpc.client;

import com.nexus.hub.grpc.common.TenantContext;
import com.nexus.hub.security.UserPrincipal;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class GrpcContextHelper {

    /**
     * Builds the TenantContext from the authenticated principal. There is deliberately NO fallback
     * identity: calling ai-service without an authenticated user is a programming/security error.
     *
     * @throws AuthenticationCredentialsNotFoundException when no authenticated UserPrincipal is present
     */
    public TenantContext buildTenantContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException(
                    "An authenticated user is required to call the AI service");
        }

        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        List<String> roles = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return TenantContext.newBuilder()
                .setTenantId(principal.getTenantId().toString())
                .setUserId(principal.getId().toString())
                .setTraceId(traceId)
                .addAllRoles(roles)
                .build();
    }

    /** user_id sent for background (non-user) calls made by core-service itself. */
    public static final String SYSTEM_USER_ID = "system:core-service";
    /** Role sent for background (non-user) calls made by core-service itself. */
    public static final String SYSTEM_ROLE = "ROLE_SYSTEM";

    /**
     * Builds a TenantContext for a background/system caller acting on behalf of a KNOWN tenant (the
     * tenant id must come from a trusted source, e.g. a persisted row or a validated internal event).
     * Intended ONLY for DocumentIngestCompletedConsumer's orphan-vector cleanup; request-path code
     * must use {@link #buildTenantContext()}. There is no random/default tenant fallback.
     *
     * @throws IllegalArgumentException when tenantId is null
     */
    public TenantContext buildSystemTenantContext(UUID tenantId, String traceId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required for a system TenantContext");
        }
        String effectiveTraceId = (traceId != null && !traceId.isBlank()) ? traceId : MDC.get("traceId");
        if (effectiveTraceId == null || effectiveTraceId.isBlank()) {
            effectiveTraceId = UUID.randomUUID().toString();
        }
        return TenantContext.newBuilder()
                .setTenantId(tenantId.toString())
                .setUserId(SYSTEM_USER_ID)
                .setTraceId(effectiveTraceId)
                .addRoles(SYSTEM_ROLE)
                .build();
    }
}
