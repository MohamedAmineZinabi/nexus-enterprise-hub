package com.nexus.hub.modules.user.entity;

public enum Role {
    /** Organization (tenant) owner/administrator; assigned to the registering user of a new org. */
    ADMIN,
    ANALYST,
    VIEWER,
    /**
     * Platform (infrastructure) operator: the only role allowed to read non-health actuator endpoints.
     * Never assigned by registration or any API; it can only be granted directly in the database.
     */
    PLATFORM_OPERATOR
}
