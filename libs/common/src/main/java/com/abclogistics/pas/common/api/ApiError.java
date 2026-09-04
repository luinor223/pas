package com.abclogistics.pas.common.api;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** JSON error body returned by every service. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldViolation> violations
) {
    private static final Pattern UUID_PATH_SEGMENT = Pattern.compile(
            "(?i)(?<=/)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?=/|$)");

    public ApiError {
        path = path == null ? null : UUID_PATH_SEGMENT.matcher(path).replaceAll("{id}");
    }

    public record FieldViolation(String field, String message) { }

    public static ApiError of(int status, String error, String message, String path) {
        return of(status, error, "HTTP_" + status, message, path);
    }

    public static ApiError of(int status, String error, String code, String message, String path) {
        return new ApiError(Instant.now(), status, error, code, message, path, List.of());
    }
}
