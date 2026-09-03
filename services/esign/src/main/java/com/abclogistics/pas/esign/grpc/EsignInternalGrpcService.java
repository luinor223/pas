package com.abclogistics.pas.esign.grpc;

import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.service.SigningSessionService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@GrpcService
public class EsignInternalGrpcService extends EsignInternalGrpc.EsignInternalImplBase {

    private static final Logger log = LoggerFactory.getLogger(EsignInternalGrpcService.class);

    private final SigningSessionService sessionService;

    public EsignInternalGrpcService(SigningSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    @Transactional
    public void createSigningSession(CreateSigningSessionRequest request,
                                      StreamObserver<CreateSigningSessionResponse> responseObserver) {
        try {
            UUID idempotencyKey = UUID.fromString(request.getIdempotencyKey());
            UUID documentId = UUID.fromString(request.getDocumentId());

            String signerName = request.getSignerName().isEmpty() ? "" : request.getSignerName();
            String signerEmail = request.getSignerEmail().isEmpty() ? "" : request.getSignerEmail();

            SigningSession session = sessionService.createSession(
                request.getDocumentType(),
                documentId,
                request.getDocumentNo(),
                null,
                signerName,
                signerEmail,
                idempotencyKey,
                UUID.randomUUID(),
                null
            );

            responseObserver.onNext(CreateSigningSessionResponse.newBuilder()
                .setSessionId(session.getId().toString())
                .setStatus(session.getStatus().name())
                .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("CreateSigningSession failed: {}", e.getMessage(), e);
            Status status = Status.INTERNAL;
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                status = Status.ALREADY_EXISTS;
            } else if (e.getMessage() != null && e.getMessage().contains("already an active")) {
                status = Status.FAILED_PRECONDITION;
            }
            responseObserver.onError(status
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }
}
