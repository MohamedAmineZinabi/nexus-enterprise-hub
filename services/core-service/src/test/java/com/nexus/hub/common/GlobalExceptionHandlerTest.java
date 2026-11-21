package com.nexus.hub.common;

import com.nexus.hub.common.dto.ApiResponse;
import com.nexus.hub.security.TokenRevocationUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("MaxUploadSizeExceededException -> 413 with a safe message")
    void maxUploadSizeReturns413() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(50L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("too large").doesNotContain("Exception");
    }

    @Test
    @DisplayName("Generic exceptions -> 500 with a traceId, never the raw exception message")
    void genericExceptionHidesMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleGenericException(
                new IllegalStateException("password=hunter2 at jdbc:postgresql://db/internal"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiResponse<Void> body = response.getBody();
        assertThat(body.getMessage()).doesNotContain("hunter2").doesNotContain("jdbc");
        assertThat(String.valueOf(body.getErrors())).doesNotContain("hunter2");

        @SuppressWarnings("unchecked")
        String traceId = ((Map<String, String>) body.getErrors()).get("traceId");
        assertThat(UUID.fromString(traceId)).isNotNull();
        assertThat(body.getMessage()).contains(traceId);
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException -> 400")
    void typeMismatchReturns400() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "taskId", null, new IllegalArgumentException("bad"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("taskId");
    }

    @Test
    @DisplayName("AuthenticationCredentialsNotFoundException -> 401")
    void missingCredentialsReturns401() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingCredentials(
                new AuthenticationCredentialsNotFoundException("no principal"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Revocation store outage (e.g. on logout) -> 503")
    void revocationUnavailableReturns503() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleApiException(
                new TokenRevocationUnavailableException("Token revocation service is temporarily unavailable", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
