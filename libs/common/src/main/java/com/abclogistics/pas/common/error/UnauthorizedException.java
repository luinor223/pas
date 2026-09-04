package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends DomainException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }

    public UnauthorizedException(String publicCode, String publicMessage, String diagnosticMessage) {
        super(HttpStatus.UNAUTHORIZED, publicCode, publicMessage, diagnosticMessage);
    }
}
