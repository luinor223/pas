package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends DomainException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }

    public ForbiddenException(String publicCode, String publicMessage, String diagnosticMessage) {
        super(HttpStatus.FORBIDDEN, publicCode, publicMessage, diagnosticMessage);
    }
}
