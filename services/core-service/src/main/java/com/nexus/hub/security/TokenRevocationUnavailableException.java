package com.nexus.hub.security;

import com.nexus.hub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the JWT revocation store (Redis) cannot be consulted or updated.
 * Revocation is fail-closed: callers must reject the request with 503 rather than assume
 * "not revoked" / "revoked successfully".
 */
public class TokenRevocationUnavailableException extends ApiException {

    public TokenRevocationUnavailableException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
        if (cause != null) {
            initCause(cause);
        }
    }
}
