package com.nexus.hub.common.exception;

import org.springframework.http.HttpStatus;

public class AiServiceException extends ApiException {
    public AiServiceException(String message) {
        super(message, HttpStatus.BAD_GATEWAY);
    }

    public AiServiceException(String message, HttpStatus status) {
        super(message, status);
    }
}
