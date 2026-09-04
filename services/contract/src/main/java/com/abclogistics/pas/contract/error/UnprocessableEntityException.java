package com.abclogistics.pas.contract.error;

import com.abclogistics.pas.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class UnprocessableEntityException extends DomainException {
    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    public UnprocessableEntityException(String publicCode, String publicMessage, String diagnosticMessage) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, publicCode, publicMessage, diagnosticMessage);
    }
}
