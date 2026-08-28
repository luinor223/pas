package com.abclogistics.pas.contract.error;

import com.abclogistics.pas.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class FailedPreconditionException extends DomainException {
    public FailedPreconditionException(String message) {
        super(HttpStatus.PRECONDITION_FAILED, message);
    }
}
