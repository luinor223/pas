package com.abclogistics.pas.common.error;

import io.grpc.Status;
import org.springframework.security.access.AccessDeniedException;

/** Translates exceptions to gRPC Status the way GlobalExceptionHandler maps them to HTTP. */
public final class GrpcStatusMapper {

    private GrpcStatusMapper() { }

    public static Status toStatus(Throwable e) {
        if (e instanceof IllegalArgumentException) return Status.INVALID_ARGUMENT;
        if (e instanceof AccessDeniedException) return Status.PERMISSION_DENIED;
        if (e instanceof DomainException de) {
            return switch (de.getStatus()) {
                case NOT_FOUND -> Status.NOT_FOUND;
                case PRECONDITION_FAILED, CONFLICT, UNPROCESSABLE_CONTENT -> Status.FAILED_PRECONDITION;
                case FORBIDDEN -> Status.PERMISSION_DENIED;
                case UNAUTHORIZED -> Status.UNAUTHENTICATED;
                default -> Status.INTERNAL;
            };
        }
        return Status.INTERNAL;
    }
}
