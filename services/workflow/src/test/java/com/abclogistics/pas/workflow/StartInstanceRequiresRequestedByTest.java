package com.abclogistics.pas.workflow;

import com.abclogistics.pas.workflow.grpc.StartInstanceRequest;
import com.abclogistics.pas.workflow.grpc.StartInstanceResponse;
import com.abclogistics.pas.workflow.controller.grpc.WorkflowInternalGrpcService;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowActionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import com.abclogistics.pas.workflow.service.WorkflowInstanceService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code requested_by} is required (registry §5): the instance records who asked for it, and
 * audit reads that id back. A start with no submitter is rejected rather than stored as null.
 */
class StartInstanceRequiresRequestedByTest {

    private final WorkflowInstanceService instances = mock(WorkflowInstanceService.class);
    private final WorkflowInternalGrpcService service = new WorkflowInternalGrpcService(
            instances, mock(WorkflowStepInstanceRepository.class),
            mock(StepAssigneeRepository.class), mock(WorkflowActionRepository.class));

    @Test
    void aBlankRequestedByIsInvalidArgument() {
        StatusRuntimeException error = startWith("");

        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(error.getStatus().getDescription()).contains("requested_by is required");
    }

    @Test
    void aMalformedRequestedByIsInvalidArgument() {
        StatusRuntimeException error = startWith("not-a-uuid");

        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(error.getStatus().getDescription()).contains("not a uuid");
    }

    /** The rejection happens before any state is created — no half-started instance to clean up. */
    @Test
    void neitherRejectionEverStartsAnInstance() {
        startWith("");
        startWith("not-a-uuid");

        verify(instances, never()).startInstance(anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any());
    }

    private StatusRuntimeException startWith(String requestedById) {
        @SuppressWarnings("unchecked")
        StreamObserver<StartInstanceResponse> observer = mock(StreamObserver.class);
        service.startInstance(StartInstanceRequest.newBuilder()
                .setDocumentType("CONTRACT")
                .setDocumentId(UUID.randomUUID().toString())
                .setDocumentNo("HD-2026-0001")
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setRequestedBy(requestedById)
                .setRequestedByName("Nguyen Thi Lan")
                .build(), observer);

        verify(observer, never()).onCompleted();
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captor.capture());
        return (StatusRuntimeException) captor.getValue();
    }
}
