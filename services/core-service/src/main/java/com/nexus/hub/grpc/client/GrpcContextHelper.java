package com.nexus.hub.grpc.client;

import com.nexus.hub.grpc.common.TenantContext;
import com.nexus.hub.security.UserPrincipal;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class GrpcContextHelper {

    public TenantContext buildTenantContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
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

        // Fallback for unauthenticated background or system flows
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        return TenantContext.newBuilder()
                .setTenantId(UUID.randomUUID().toString())
                .setUserId(UUID.randomUUID().toString())
                .setTraceId(traceId)
                .addAllRoles(List.of("ROLE_SYSTEM"))
                .build();
    }

    public TenantContext buildTenantContext(UUID tenantId, UUID userId, List<String> roles) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        return TenantContext.newBuilder()
                .setTenantId(tenantId != null ? tenantId.toString() : UUID.randomUUID().toString())
                .setUserId(userId != null ? userId.toString() : UUID.randomUUID().toString())
                .setTraceId(traceId)
                .addAllRoles(roles != null ? roles : Collections.emptyList())
                .build();
    }
}
