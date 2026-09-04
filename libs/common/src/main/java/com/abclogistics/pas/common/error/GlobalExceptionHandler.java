package com.abclogistics.pas.common.error;

import com.abclogistics.pas.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex, HttpServletRequest req) {
        String code = ex.getPublicCode() == null ? domainCode(ex.getStatus()) : ex.getPublicCode();
        String message = ex.hasPublicMessage() ? ex.getPublicMessage() : defaultMessage(ex.getStatus());
        if (ex.hasPublicMessage()) {
            log.debug("Domain error for {} {} [{}]: {}",
                    req.getMethod(), req.getRequestURI(), code, ex.getMessage(), ex);
        } else {
            log.warn("Suppressed internal domain error for {} {} [{}]: {}",
                    req.getMethod(), req.getRequestURI(), code, ex.getMessage(), ex);
        }
        return build(ex.getStatus(), code, message, req.getRequestURI(), List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The record changed concurrently; retry.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        log.debug("Request validation failed for {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiError.FieldViolation(e.getField(),
                        "Invalid value."))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                req.getRequestURI(), violations);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.warn("Access denied for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        log.warn("Authentication failed for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("Invalid request for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request contains an invalid value.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.warn("Request parameter type mismatch for {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request contains an invalid value.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("Malformed request body for {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is malformed.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ApiError> handleMissingRequestValue(Exception ex, HttpServletRequest req) {
        log.warn("Required request value missing for {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_VALUE",
                "A required request value is missing.", req.getRequestURI(), List.of());
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiError> handleRequestBinding(ServletRequestBindingException ex, HttpServletRequest req) {
        log.warn("Request binding failed for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request could not be understood.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        log.warn("Unsupported method for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "This request method is not supported for the requested resource.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        log.warn("Unsupported media type for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "The request content type is not supported.", req.getRequestURI(), List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest req) {
        log.warn("No acceptable response type for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_ACCEPTABLE, "RESPONSE_TYPE_NOT_ACCEPTABLE",
                "The requested response format is not supported.", req.getRequestURI(), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(
            MaxUploadSizeExceededException ex, HttpServletRequest req) {
        log.warn("Upload too large for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                "The uploaded file is too large.", req.getRequestURI(), List.of());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMalformedMultipart(MultipartException ex, HttpServletRequest req) {
        log.warn("Malformed multipart request for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_MULTIPART_REQUEST",
                "The multipart request is malformed.", req.getRequestURI(), List.of());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleUnknownRoute(Exception ex, HttpServletRequest req) {
        log.warn("No route for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "The requested resource was not found.", req.getRequestURI(), List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        Throwable cause = ex.getMostSpecificCause();
        String sqlState = null;
        if (cause instanceof org.hibernate.exception.ConstraintViolationException hce) {
            sqlState = hce.getSQLState();
        } else if (cause instanceof java.sql.SQLException sqle) {
            sqlState = sqle.getSQLState();
        }
        if (sqlState == null && cause != null) {
            Throwable t = cause.getCause();
            while (t != null) {
                if (t instanceof java.sql.SQLException s) { sqlState = s.getSQLState(); break; }
                t = t.getCause();
            }
        }
        if ("23514".equals(sqlState)) {
            log.warn("Database check constraint rejected {} {}", req.getMethod(), req.getRequestURI(), ex);
            return build(HttpStatus.BAD_REQUEST, "INVALID_DATA",
                    "The request contains a value that is not allowed.",
                    req.getRequestURI(), List.of());
        }
        log.warn("Database integrity conflict for {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        log.debug("Request constraint validation failed for {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        String msg = ex.getConstraintViolations().stream()
                .findFirst().map(v -> "Invalid value.").orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg, req.getRequestURI(), List.of());
    }

    /**
     * Catch-all. Spring's own MVC exceptions implement {@link ErrorResponse} and keep their proper status
     * (the explicit handlers above cover the common ones); anything else that reaches here is a genuine 5xx
     * we log with the stack trace, since it is otherwise invisible in the app logs. Client messages stay
     * safe and canned rather than leaking framework detail.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        if (ex instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            if (status.is5xxServerError()) {
                log.error("Framework error on {} {}", req.getMethod(), req.getRequestURI(), ex);
            } else {
                log.warn("Framework error on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
            }
            return build(status, domainCode(status), defaultMessage(status), req.getRequestURI(), List.of());
        }
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                defaultMessage(HttpStatus.INTERNAL_SERVER_ERROR), req.getRequestURI(), List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, String path,
                                           List<ApiError.FieldViolation> violations) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), code,
                message, path, violations);
        return ResponseEntity.status(status).body(body);
    }

    private static String domainCode(HttpStatus status) {
        return switch (status.value()) {
            case 401 -> "AUTHENTICATION_FAILED";
            case 403 -> "ACTION_FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "STATE_CONFLICT";
            case 412 -> "INVALID_STATE_TRANSITION";
            case 422 -> "BUSINESS_RULE_VIOLATION";
            case 503 -> "DEPENDENCY_UNAVAILABLE";
            default -> "DOMAIN_ERROR";
        };
    }

    private static String defaultMessage(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "The request contains an invalid value.";
            case 401 -> "Authentication failed.";
            case 403 -> "You do not have permission to perform this action.";
            case 404 -> "The requested resource was not found.";
            case 409 -> "This action cannot be completed because the record is no longer in the required state.";
            case 412 -> "This action is not allowed in the document's current state.";
            case 422 -> "The request does not satisfy a required business rule.";
            case 503 -> "A required service is temporarily unavailable. Try again shortly.";
            default -> "The request could not be completed.";
        };
    }
}
