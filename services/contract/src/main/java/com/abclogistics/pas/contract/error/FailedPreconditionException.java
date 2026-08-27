package com.abclogistics.pas.contract.error;

import com.abclogistics.pas.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * State-machine violation or missing prerequisite -> gRPC FAILED_PRECONDITION, REST 412.
 * Same mapping workflow-service uses, so ValidateStartable's FAILED_PRECONDITION surfaces as 412.
 */
public class FailedPreconditionException extends DomainException {
    public FailedPreconditionException(String message) {
        super(HttpStatus.PRECONDITION_FAILED, message);
    }
}
