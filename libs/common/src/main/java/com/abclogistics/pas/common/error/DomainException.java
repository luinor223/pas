package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

/** Base for domain errors; carries the HTTP status the handler returns. */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String publicCode;
    private final String publicMessage;

    protected DomainException(HttpStatus status, String message) {
        this(status, null, null, message);
    }

    /** Explicitly publishes a reviewed client message; the diagnostic message remains log-only. */
    protected DomainException(HttpStatus status, String publicCode, String publicMessage,
                              String diagnosticMessage) {
        super(diagnosticMessage);
        this.status = status;
        this.publicCode = publicCode;
        this.publicMessage = publicMessage;
    }

    public String getPublicCode() {
        return publicCode;
    }

    public String getPublicMessage() {
        return publicMessage;
    }

    public boolean hasPublicMessage() {
        return publicMessage != null && !publicMessage.isBlank();
    }

    public HttpStatus getStatus() {
        return status;
    }
}
