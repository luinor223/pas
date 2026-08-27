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

/**
 * Thin wrapper over {@code WorkflowInternal} (D16, gRPC internal-only).
 *
 * <p>{@code NOT_FOUND} from {@link #getInstanceByDocument} is not an error: it is D4's dispatch
 * window, rendered as {@code INITIALIZATION_PENDING} from local status and never retried here.
 */
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

    /**
     * Read-only pre-submit check. Run BEFORE the submit commit so a document type with no active
     * definition fails fast as a 412, instead of committing and then parking in the outbox for a
     * relay to retry against a configuration that will never appear.
     */
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

    /**
     * Called only by the outbox relay, never inline with the submit transaction (D4). The
     * idempotency key is the one generated at submit, so a retry after a lost ack resolves to the
     * same instance rather than starting a second one.
     */
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

    /**
     * Retried on NOT_FOUND — the instance may not exist yet (M2). The reason is not sent: the
     * proto carries no field for it, and it belongs in this service's own audit trail anyway.
     */
    public void cancelInstance(UUID documentId, String documentTypeCode, UUID idempotencyKey) {
        deadlined().cancelInstance(CancelInstanceRequest.newBuilder()
                .setDocumentType(documentTypeCode)
                .setDocumentId(documentId.toString())
                .setIdempotencyKey(idempotencyKey.toString())
                .build());
    }

    /**
     * Empty means "no instance yet", which is a normal state during D4's dispatch window — the
     * caller renders it from local status. It is deliberately not an exception: an empty result is
     * not a failure and must not be retried.
     */
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

    /** A fresh deadline per call — a stub-level deadline is absolute and expires once, not per use. */
    private WorkflowInternalGrpc.WorkflowInternalBlockingStub deadlined() {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    /**
     * Bound to the context lifecycle: a ManagedChannel owns netty event-loop threads, so a channel
     * left open across a context restart leaks them.
     */
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
