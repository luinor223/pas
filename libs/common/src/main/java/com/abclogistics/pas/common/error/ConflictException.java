package com.abclogistics.pas.common.error;

import org.springframework.http.HttpStatus;

/** State conflicts: duplicate keys, optimistic-lock failures, disallowed transitions. */
public class ConflictException extends DomainException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
