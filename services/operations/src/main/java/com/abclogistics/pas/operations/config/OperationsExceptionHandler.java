package com.abclogistics.pas.operations.config;

import com.abclogistics.pas.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class OperationsExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        String msg = "Duplicate record or constraint violation";
        if (ex.getMostSpecificCause() != null && ex.getMostSpecificCause().getMessage() != null) {
            String cause = ex.getMostSpecificCause().getMessage().toLowerCase();
            if (cause.contains("period_code") || cause.contains("uq") || cause.contains("unique")) {
                msg = "Period or record already exists";
            }
        }
        ApiError body = new ApiError(Instant.now(), HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                msg, req.getRequestURI(), List.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
