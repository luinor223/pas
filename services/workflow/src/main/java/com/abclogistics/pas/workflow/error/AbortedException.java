package com.abclogistics.pas.workflow.error;

import com.abclogistics.pas.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * D5 optimistic-lock loss -> gRPC ABORTED, REST 409.
 */
public class AbortedException extends DomainException {
    public AbortedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
