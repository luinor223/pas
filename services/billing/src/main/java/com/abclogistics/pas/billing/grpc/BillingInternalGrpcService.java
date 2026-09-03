package com.abclogistics.pas.billing.grpc;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.billing.grpc.BillingInternalGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

@GrpcService
public class BillingInternalGrpcService extends BillingInternalGrpc.BillingInternalImplBase {

    private final StatementService statementService;
    private final PaymentStatementRepository statementRepo;

    public BillingInternalGrpcService(StatementService statementService,
                                       PaymentStatementRepository statementRepo) {
        this.statementService = statementService;
        this.statementRepo = statementRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public void getSigningPayload(GetSigningPayloadRequest request,
                                   StreamObserver<GetSigningPayloadResponse> responseObserver) {
        try {
            String statementId = request.getId();
            PaymentStatement statement = statementRepo.findById(java.util.UUID.fromString(statementId))
                .orElse(null);

            if (statement == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Statement not found: " + statementId)
                    .asRuntimeException());
                return;
            }

            PaymentStatement.StatementStatus status = statement.getStatus();
            if (status != PaymentStatement.StatementStatus.APPROVED
                && status != PaymentStatement.StatementStatus.SIGNING) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Statement must be APPROVED or SIGNING for signing payload")
                    .asRuntimeException());
                return;
            }

            responseObserver.onNext(GetSigningPayloadResponse.newBuilder()
                .setDocumentNo(statement.getStatementNo())
                .setSignerName(statement.getCustomerName() != null ? statement.getCustomerName() : "")
                .setSignerEmail("")
                .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }
}
