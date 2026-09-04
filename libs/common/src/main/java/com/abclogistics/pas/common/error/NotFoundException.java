package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends DomainException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    public NotFoundException(String publicCode, String publicMessage, String diagnosticMessage) {
        super(HttpStatus.NOT_FOUND, publicCode, publicMessage, diagnosticMessage);
    }
}
