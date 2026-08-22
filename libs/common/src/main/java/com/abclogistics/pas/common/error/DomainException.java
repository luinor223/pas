package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

/** Base for domain errors; carries the HTTP status the handler returns. */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;

    protected DomainException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
