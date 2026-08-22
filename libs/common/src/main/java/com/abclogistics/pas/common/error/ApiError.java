package com.abclogistics.pas.common.error;

import java.time.Instant;
import java.util.List;

/** JSON error body returned by every service. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> violations
) {
    public record FieldViolation(String field, String message) { }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of());
    }
}
