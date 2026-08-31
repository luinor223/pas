package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

/**
 * State-machine violation or missing prerequisite -> gRPC FAILED_PRECONDITION, REST 412.
 */
public class FailedPreconditionException extends DomainException {
    public FailedPreconditionException(String message) {
        super(HttpStatus.PRECONDITION_FAILED, message);
    }
}
