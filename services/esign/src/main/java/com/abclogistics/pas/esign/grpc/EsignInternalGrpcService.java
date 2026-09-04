package com.abclogistics.pas.esign.grpc;

import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.service.SigningSessionService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import java.util.UUID;

@GrpcService
public class EsignInternalGrpcService extends EsignInternalGrpc.EsignInternalImplBase {

    private static final Logger log = LoggerFactory.getLogger(EsignInternalGrpcService.class);

    private final SigningSessionService sessionService;

    public EsignInternalGrpcService(SigningSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void createSigningSession(CreateSigningSessionRequest request,
                                      StreamObserver<CreateSigningSessionResponse> responseObserver) {
        try {
            SigningSession session = sessionService.createSession(
                request.getDocumentType(),
                UUID.fromString(request.getDocumentId()),
                blankToNull(request.getDocumentNo()),
                blankToNull(request.getCustomerName()),
                request.getSignerName(),
                request.getSignerEmail(),
                UUID.fromString(request.getIdempotencyKey()),
                uuidOrNull(request.getRequestedBy()),
                blankToNull(request.getRequestedByName()));

            responseObserver.onNext(CreateSigningSessionResponse.newBuilder()
                .setSessionId(session.getId().toString())
                .setStatus(session.getStatus().name())
                .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("CreateSigningSession failed: {}", e.getMessage(), e);
            responseObserver.onError(mapToStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static UUID uuidOrNull(String s) {
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    private static Status mapToStatus(Exception e) {
        if (e instanceof IllegalArgumentException) return Status.INVALID_ARGUMENT;
        if (e instanceof FailedPreconditionException) return Status.FAILED_PRECONDITION;
        return Status.INTERNAL;
    }
}
