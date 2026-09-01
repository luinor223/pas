package com.abclogistics.pas.workflow.config;

import com.abclogistics.pas.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Maps DB constraint violations (e.g., duplicate version_no, duplicate active) to 409
 * instead of 500. Complements GlobalExceptionHandler.java:22 which handles DomainException/optimistic lock.
 * Residual P3 from review: without this, V1__init_workflow.sql:24 uq_workflow_definition_type_version
 * would bubble as 500 not 409 — won't reproduce under DocumentTypeConfig FOR UPDATE lock, but safe fallback.
 */
@RestControllerAdvice
public class WorkflowExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        String msg = "Duplicate version or active workflow definition — retry";
        // Keep cause for logs but don't leak SQL to client
        ApiError body = new ApiError(Instant.now(), HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                msg, req.getRequestURI(), List.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
