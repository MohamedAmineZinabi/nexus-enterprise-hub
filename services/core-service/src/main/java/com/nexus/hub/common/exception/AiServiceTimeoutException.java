package com.nexus.hub.common.exception;

import org.springframework.http.HttpStatus;

public class AiServiceTimeoutException extends ApiException {
    public AiServiceTimeoutException(String message) {
        super(message, HttpStatus.GATEWAY_TIMEOUT);
    }
}
