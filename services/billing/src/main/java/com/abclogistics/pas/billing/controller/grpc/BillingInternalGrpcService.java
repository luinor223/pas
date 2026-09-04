package com.abclogistics.pas.billing.controller.grpc;

import com.abclogistics.pas.billing.grpc.*;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.billing.service.StatementService.SigningPayload;
import com.abclogistics.pas.common.error.GrpcStatusMapper;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.util.UUID;

@GrpcService
public class BillingInternalGrpcService extends BillingInternalGrpc.BillingInternalImplBase {

    private final StatementService statementService;

    public BillingInternalGrpcService(StatementService statementService) {
        this.statementService = statementService;
    }

    @Override
    public void getSigningPayload(GetSigningPayloadRequest request,
                                   StreamObserver<GetSigningPayloadResponse> responseObserver) {
        try {
            SigningPayload payload = statementService.signingPayload(UUID.fromString(request.getId()));
            responseObserver.onNext(GetSigningPayloadResponse.newBuilder()
                .setDocumentNo(payload.documentNo())
                .setSignerName(payload.signerName())
                .setSignerEmail(payload.signerEmail())
                .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(GrpcStatusMapper.toStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
