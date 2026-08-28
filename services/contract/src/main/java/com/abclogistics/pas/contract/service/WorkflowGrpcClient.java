package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.error.FailedPreconditionException;
import com.abclogistics.pas.workflow.grpc.CancelInstanceRequest;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentRequest;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import com.abclogistics.pas.workflow.grpc.StartInstanceRequest;
import com.abclogistics.pas.workflow.grpc.ValidateStartableRequest;
import com.abclogistics.pas.workflow.grpc.WorkflowInternalGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Thin wrapper over {@code WorkflowInternal} (D16). NOT_FOUND is D4's dispatch window, not an error. */
@Component
public class WorkflowGrpcClient {

    private final ManagedChannel channel;
    private final WorkflowInternalGrpc.WorkflowInternalBlockingStub stub;

    public WorkflowGrpcClient(@Value("${workflow.grpc.host:localhost}") String host,
                              @Value("${workflow.grpc.port:50056}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = WorkflowInternalGrpc.newBlockingStub(channel);
    }

    protected WorkflowGrpcClient() {
        this.channel = null;
        this.stub = null;
    }

    public void validateStartable(String documentTypeCode) {
        try {
            deadlined().validateStartable(ValidateStartableRequest.newBuilder()
                    .setDocumentType(documentTypeCode)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
                    || e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new FailedPreconditionException(
                        "No approval workflow is configured for %s: %s"
                                .formatted(documentTypeCode, e.getStatus().getDescription()));
            }
            throw e;
        }
    }

    public UUID startInstance(UUID idempotencyKey, String documentTypeCode, UUID documentId,
                              String documentNo, String customerName, String priority,
                              UUID requestedById, String requestedByName) {
        StartInstanceRequest request = StartInstanceRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey.toString())
                .setDocumentType(documentTypeCode)
                .setDocumentId(documentId.toString())
                .setDocumentNo(documentNo)
                .setCustomerName(customerName == null ? "" : customerName)
                .setPriority(priority == null ? "" : priority)
                .setRequestedById(requestedById == null ? "" : requestedById.toString())
                .setRequestedByName(requestedByName == null ? "" : requestedByName)
                .build();
        return UUID.fromString(deadlined().startInstance(request).getInstanceId());
    }

    public enum CancelOutcome {
        CANCELLED,
        ALREADY_ACTIONED,
        NOT_FOUND
    }

    public CancelOutcome cancelInstance(UUID documentId, String documentTypeCode, UUID idempotencyKey) {
        try {
            deadlined().cancelInstance(CancelInstanceRequest.newBuilder()
                    .setDocumentType(documentTypeCode)
                    .setDocumentId(documentId.toString())
                    .setIdempotencyKey(idempotencyKey.toString())
                    .build());
            return CancelOutcome.CANCELLED;
        } catch (StatusRuntimeException e) {
            return switch (e.getStatus().getCode()) {
                case NOT_FOUND -> CancelOutcome.NOT_FOUND;
                case FAILED_PRECONDITION -> CancelOutcome.ALREADY_ACTIONED;
                // transport failures are not answers: a cancel that never landed is not definitive
                default -> throw e;
            };
        }
    }

    public Optional<GetInstanceByDocumentResponse> getInstanceByDocument(String documentTypeCode,
                                                                        UUID documentId) {
        try {
            return Optional.of(deadlined().getInstanceByDocument(
                    GetInstanceByDocumentRequest.newBuilder()
                            .setDocumentType(documentTypeCode)
                            .setDocumentId(documentId.toString())
                            .build()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private WorkflowInternalGrpc.WorkflowInternalBlockingStub deadlined() {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (channel == null) {
            return;
        }
        channel.shutdown();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
