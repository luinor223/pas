package com.abclogistics.pas.pricing.service;

import com.abclogistics.pas.common.error.FailedPreconditionException;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Thin blocking wrapper over {@code WorkflowInternal} (D16, D4). Tests stub it with @MockitoBean. */
@Component
public class WorkflowGrpcClient {

    private final ManagedChannel channel;
    private final WorkflowInternalGrpc.WorkflowInternalBlockingStub stub;

    public WorkflowGrpcClient(@Value("${workflow.grpc.host:localhost}") String host,
                              @Value("${workflow.grpc.port:50056}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = WorkflowInternalGrpc.newBlockingStub(channel);
    }

    /** For test doubles that don't need a real channel. */
    protected WorkflowGrpcClient() {
        this.channel = null;
        this.stub = null;
    }

    /** Read-only pre-submit check; a missing/unstartable definition becomes FailedPreconditionException. */
    public void validateStartable(String documentType) {
        try {
            deadlined().validateStartable(ValidateStartableRequest.newBuilder()
                    .setDocumentType(documentType).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
                    || e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new FailedPreconditionException(
                        "No approval workflow is configured for %s: %s"
                                .formatted(documentType, e.getStatus().getDescription()));
            }
            throw e;
        }
    }

    /** Idempotent on idempotencyKey — safe to retry until it succeeds (D4). */
    public void startInstance(UUID idempotencyKey, String documentType, UUID documentId, String documentNo,
                              String customerName, String priority, UUID requestedById, String requestedByName) {
        deadlined().startInstance(StartInstanceRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey.toString())
                .setDocumentType(documentType)
                .setDocumentId(documentId.toString())
                .setDocumentNo(documentNo)
                .setCustomerName(customerName == null ? "" : customerName)
                .setPriority(priority == null ? "NORMAL" : priority)
                .setRequestedBy(requestedById == null ? "" : requestedById.toString())
                .setRequestedByName(requestedByName == null ? "" : requestedByName)
                .build());
    }

    private WorkflowInternalGrpc.WorkflowInternalBlockingStub deadlined() {
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) channel.shutdown();
    }
}
