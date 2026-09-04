package com.abclogistics.pas.common.error;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcStatusMapperTest {

    private static Status.Code code(Throwable e) {
        return GrpcStatusMapper.toStatus(e).getCode();
    }

    @Test
    void mapsNonDomainExceptions() {
        assertThat(code(new IllegalArgumentException())).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(code(new AccessDeniedException("no"))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(code(new RuntimeException())).isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void mapsDomainExceptionsByHttpStatus() {
        assertThat(code(domain(HttpStatus.NOT_FOUND))).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(code(domain(HttpStatus.PRECONDITION_FAILED))).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(code(domain(HttpStatus.CONFLICT))).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(code(domain(HttpStatus.UNPROCESSABLE_CONTENT))).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(code(domain(HttpStatus.FORBIDDEN))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(code(domain(HttpStatus.UNAUTHORIZED))).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(code(domain(HttpStatus.BAD_GATEWAY))).isEqualTo(Status.Code.INTERNAL);
    }

    private static DomainException domain(HttpStatus status) {
        return new DomainException(status, status.name()) { };
    }
}
