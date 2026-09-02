package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

/**
 * Maps gRPC UNAVAILABLE (00-registry.md:116: only retryable) to HTTP 503.
 * Caller should retry with backoff; 409 would be non-retryable.
 */
public class ServiceUnavailableException extends DomainException {
    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
