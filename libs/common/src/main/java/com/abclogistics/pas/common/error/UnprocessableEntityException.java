package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

/** Well-formed request that violates a business rule. */
public class UnprocessableEntityException extends DomainException {
    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
