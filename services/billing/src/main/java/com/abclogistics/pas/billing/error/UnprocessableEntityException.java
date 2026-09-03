package com.abclogistics.pas.billing.error;

import com.abclogistics.pas.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class UnprocessableEntityException extends DomainException {
    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
