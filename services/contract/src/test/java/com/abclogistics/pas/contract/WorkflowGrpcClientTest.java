package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient.CancelOutcome;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient.CancelResult;
import com.abclogistics.pas.workflow.grpc.CancelInstanceRequest;
import com.abclogistics.pas.workflow.grpc.CancelInstanceResponse;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentRequest;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import com.abclogistics.pas.workflow.grpc.StartInstanceRequest;
import com.abclogistics.pas.workflow.grpc.StartInstanceResponse;
import com.abclogistics.pas.workflow.grpc.ValidateStartableRequest;
import com.abclogistics.pas.workflow.grpc.ValidateStartableResponse;
import com.abclogistics.pas.workflow.grpc.WorkflowInternalGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client's own behaviour, against a real gRPC server on a loopback port.
 *
 * <p>Everything else mocks {@link WorkflowGrpcClient}, which is right for pinning ContractService's
 * ordering but proves nothing about the translation this class performs: status-code mapping,
 * absent-instance handling, request field mapping, and per-call deadlines. Those only exist on the
 * wire, so this test puts a server on the other end of one. No Spring context and no Docker.
 */
class WorkflowGrpcClientTest {

    private Server server;
    private WorkflowGrpcClient client;
    private final FakeWorkflow workflow = new FakeWorkflow();

    @BeforeEach
    void startServer() throws IOException {
        server = ServerBuilder.forPort(0).addService(workflow).build().start();
        client = new WorkflowGrpcClient("localhost", server.getPort());
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        client.shutdown();
        server.shutdown();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---- validateStartable ------------------------------------------------------------------

    @Test
    void failedPreconditionBecomesA412() {
        workflow.validateStartableError = Status.FAILED_PRECONDITION
                .withDescription("no active definition").asRuntimeException();

        assertThatThrownBy(() -> client.validateStartable("CONTRACT"))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("CONTRACT")
                .hasMessageContaining("no active definition");
    }

    @Test
    void notFoundAlsoMeansNotConfigured() {
        // An unknown document type and a type with no active definition are the same answer to
        // the caller: this document cannot start a workflow right now.
        workflow.validateStartableError = Status.NOT_FOUND.asRuntimeException();

        assertThatThrownBy(() -> client.validateStartable("CONTRACT"))
                .isInstanceOf(FailedPreconditionException.class);
    }

    @Test
    void anUnexpectedStatusIsNotSwallowed() {
        // INTERNAL is a fault, not a business answer. Mapping it to 412 would tell the user their
        // document is misconfigured when the workflow service is simply broken.
        workflow.validateStartableError = Status.INTERNAL.asRuntimeException();

        assertThatThrownBy(() -> client.validateStartable("CONTRACT"))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void validateStartableSendsTheDocumentType() {
        client.validateStartable("CONTRACT");
        assertThat(workflow.lastValidate.get().getDocumentType()).isEqualTo("CONTRACT");
    }

    // ---- getInstanceByDocument ---------------------------------------------------------------

    @Test
    void anAbsentInstanceIsAnEmptyOptionalNotAnException() {
        // D4's dispatch window: SUBMITTED with no instance yet is normal, so NOT_FOUND is an
        // answer the caller composes with local status, never a failure to retry.
        workflow.getInstanceError = Status.NOT_FOUND.asRuntimeException();

        assertThat(client.getInstanceByDocument("CONTRACT", UUID.randomUUID())).isEmpty();
    }

    @Test
    void anExistingInstanceIsReturned() {
        UUID documentId = UUID.randomUUID();
        workflow.instance = GetInstanceByDocumentResponse.newBuilder()
                .setInstanceId(UUID.randomUUID().toString())
                .setStatus("IN_PROGRESS")
                .build();

        Optional<GetInstanceByDocumentResponse> found =
                client.getInstanceByDocument("CONTRACT", documentId);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(workflow.lastGetInstance.get().getDocumentId()).isEqualTo(documentId.toString());
    }

    @Test
    void aRealFailureOnGetInstanceStillPropagates() {
        workflow.getInstanceError = Status.UNAVAILABLE.asRuntimeException();

        assertThatThrownBy(() -> client.getInstanceByDocument("CONTRACT", UUID.randomUUID()))
                .isInstanceOf(StatusRuntimeException.class);
    }

    // ---- startInstance / cancelInstance -------------------------------------------------------

    @Test
    void startInstanceSendsEveryFieldTheProtoDeclares() {
        UUID key = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        workflow.instanceId = instanceId;

        UUID returned = client.startInstance(key, "CONTRACT", documentId, "CTR-2026-0001",
                "ACME Logistics", "NORMAL", actorId, "Nguyen Thi Lan");

        assertThat(returned).isEqualTo(instanceId);
        StartInstanceRequest sent = workflow.lastStart.get();
        assertThat(sent.getIdempotencyKey()).isEqualTo(key.toString());
        assertThat(sent.getDocumentType()).isEqualTo("CONTRACT");
        assertThat(sent.getDocumentId()).isEqualTo(documentId.toString());
        assertThat(sent.getDocumentNo()).isEqualTo("CTR-2026-0001");
        assertThat(sent.getCustomerName()).isEqualTo("ACME Logistics");
        assertThat(sent.getPriority()).isEqualTo("NORMAL");
        assertThat(sent.getRequestedById()).isEqualTo(actorId.toString());
        assertThat(sent.getRequestedByName()).isEqualTo("Nguyen Thi Lan");
    }

    @Test
    void aSystemActorSendsEmptyStringsNotNulls() {
        // proto3 has no null; setting one throws. A scheduler-driven start has no actor.
        workflow.instanceId = UUID.randomUUID();

        client.startInstance(UUID.randomUUID(), "CONTRACT", UUID.randomUUID(), "CTR-2026-0002",
                null, null, null, null);

        StartInstanceRequest sent = workflow.lastStart.get();
        assertThat(sent.getCustomerName()).isEmpty();
        assertThat(sent.getRequestedById()).isEmpty();
        assertThat(sent.getRequestedByName()).isEmpty();
    }

    @Test
    void cancelInstanceSendsTheIdempotencyKey() {
        UUID documentId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        client.cancelInstance(documentId, "CONTRACT", key);

        CancelInstanceRequest sent = workflow.lastCancel.get();
        assertThat(sent.getDocumentId()).isEqualTo(documentId.toString());
        assertThat(sent.getDocumentType()).isEqualTo("CONTRACT");
        assertThat(sent.getIdempotencyKey()).isEqualTo(key.toString());
    }

    @Test
    void anOkCancelIsTheCancelledOutcome() {
        assertThat(client.cancelInstance(UUID.randomUUID(), "CONTRACT", UUID.randomUUID()))
                .isEqualTo(CancelResult.cancelled());
    }

    @Test
    void failedPreconditionCarriesWorkflowsOwnReason() {
        // The handoff reconciles against the instance rather than trusting this outcome, but the
        // reason is what reaches the caller in the 409 — losing it here is what made an
        // already-cancelled instance indistinguishable from an actioned step.
        workflow.cancelError = Status.FAILED_PRECONDITION
                .withDescription("Workflow instance not in progress, cannot cancel: CANCELLED")
                .asRuntimeException();

        CancelResult result = client.cancelInstance(UUID.randomUUID(), "CONTRACT", UUID.randomUUID());

        assertThat(result.outcome()).isEqualTo(CancelOutcome.REFUSED);
        assertThat(result.detail()).isEqualTo("Workflow instance not in progress, cannot cancel: CANCELLED");
    }

    @Test
    void notFoundIsTheInconclusiveOutcomeNotAnError() {
        // D4's dispatch window: the instance may simply not exist yet.
        workflow.cancelError = Status.NOT_FOUND.withDescription("no instance").asRuntimeException();

        assertThat(client.cancelInstance(UUID.randomUUID(), "CONTRACT", UUID.randomUUID()).outcome())
                .isEqualTo(CancelOutcome.NOT_FOUND);
    }

    @Test
    void aTransportFailureIsNotMappedToAnOutcomeAtAll() {
        // An unanswered cancel is not an answer: mapping UNAVAILABLE to any outcome would let the
        // handoff act on a call that never landed.
        workflow.cancelError = Status.UNAVAILABLE.asRuntimeException();

        assertThatThrownBy(() -> client.cancelInstance(UUID.randomUUID(), "CONTRACT", UUID.randomUUID()))
                .isInstanceOf(StatusRuntimeException.class);
    }

    // ---- deadlines ------------------------------------------------------------------------------

    @Test
    void eachCallGetsItsOwnDeadline() throws Exception {
        // A deadline set once on the stub is ABSOLUTE, not per-call: it starts running when the
        // bean is built and every call after it elapses fails with DEADLINE_EXCEEDED. Two calls
        // either side of that window prove the deadline is applied per call instead.
        client.validateStartable("CONTRACT");
        Thread.sleep(2_500); // longer than the 2s deadline
        client.validateStartable("CONTRACT");

        assertThat(workflow.validateCalls).isEqualTo(2);
    }

    // ---- the far end ----------------------------------------------------------------------------

    private static final class FakeWorkflow extends WorkflowInternalGrpc.WorkflowInternalImplBase {

        StatusRuntimeException validateStartableError;
        StatusRuntimeException cancelError;
        StatusRuntimeException getInstanceError;
        GetInstanceByDocumentResponse instance;
        UUID instanceId = UUID.randomUUID();
        int validateCalls;

        final AtomicReference<ValidateStartableRequest> lastValidate = new AtomicReference<>();
        final AtomicReference<StartInstanceRequest> lastStart = new AtomicReference<>();
        final AtomicReference<CancelInstanceRequest> lastCancel = new AtomicReference<>();
        final AtomicReference<GetInstanceByDocumentRequest> lastGetInstance = new AtomicReference<>();

        @Override
        public void validateStartable(ValidateStartableRequest request,
                                      StreamObserver<ValidateStartableResponse> observer) {
            validateCalls++;
            lastValidate.set(request);
            if (validateStartableError != null) {
                observer.onError(validateStartableError);
                return;
            }
            observer.onNext(ValidateStartableResponse.getDefaultInstance());
            observer.onCompleted();
        }

        @Override
        public void startInstance(StartInstanceRequest request,
                                  StreamObserver<StartInstanceResponse> observer) {
            lastStart.set(request);
            observer.onNext(StartInstanceResponse.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .setStatus("IN_PROGRESS")
                    .build());
            observer.onCompleted();
        }

        @Override
        public void cancelInstance(CancelInstanceRequest request,
                                   StreamObserver<CancelInstanceResponse> observer) {
            lastCancel.set(request);
            if (cancelError != null) {
                observer.onError(cancelError);
                return;
            }
            observer.onNext(CancelInstanceResponse.getDefaultInstance());
            observer.onCompleted();
        }

        @Override
        public void getInstanceByDocument(GetInstanceByDocumentRequest request,
                                          StreamObserver<GetInstanceByDocumentResponse> observer) {
            lastGetInstance.set(request);
            if (getInstanceError != null) {
                observer.onError(getInstanceError);
                return;
            }
            observer.onNext(instance == null
                    ? GetInstanceByDocumentResponse.getDefaultInstance() : instance);
            observer.onCompleted();
        }
    }
}
