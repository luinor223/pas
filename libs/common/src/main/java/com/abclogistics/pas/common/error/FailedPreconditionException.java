package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

/**
 * State-machine violation or missing prerequisite -> gRPC FAILED_PRECONDITION, REST 412.
 */
public class FailedPreconditionException extends DomainException {
    public FailedPreconditionException(String message) {
        super(HttpStatus.PRECONDITION_FAILED, message);
    }

    public FailedPreconditionException(String publicCode, String publicMessage, String diagnosticMessage) {
        super(HttpStatus.PRECONDITION_FAILED, publicCode, publicMessage, diagnosticMessage);
    }
}
