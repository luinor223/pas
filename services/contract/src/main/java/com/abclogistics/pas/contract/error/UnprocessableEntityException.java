package com.abclogistics.pas.contract.error;

import com.abclogistics.pas.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Business-rule validation failure on a well-formed request -> REST 422.
 * Used for the CTR-02 submit checks (no attachment, customer not ACTIVE, invalid validity window).
 */
public class UnprocessableEntityException extends DomainException {
    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
