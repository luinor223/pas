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
        // Handle both unique_violation (23505) and check_violation (23514) without fragile column-name string contains.
        // PG index name may not contain column name verbatim.
        Throwable cause = ex.getMostSpecificCause();
        String sqlState = null;
        if (cause instanceof org.hibernate.exception.ConstraintViolationException hce) {
            sqlState = hce.getSQLState();
        } else if (cause instanceof java.sql.SQLException sqle) {
            sqlState = sqle.getSQLState();
        }
        // also walk cause chain for SQLException
        if (sqlState == null && cause != null) {
            Throwable t = cause.getCause();
            while (t != null) {
                if (t instanceof java.sql.SQLException s) { sqlState = s.getSQLState(); break; }
                t = t.getCause();
            }
        }
        if ("23514".equals(sqlState)) {
            ApiError body = new ApiError(Instant.now(), HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
                    "Constraint violation: " + (cause != null ? cause.getMessage() : ex.getMessage()), req.getRequestURI(), List.of());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        // default 409 for unique (23505) and other integrity issues
        ApiError body = new ApiError(Instant.now(), HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                "Duplicate record or constraint violation", req.getRequestURI(), List.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
