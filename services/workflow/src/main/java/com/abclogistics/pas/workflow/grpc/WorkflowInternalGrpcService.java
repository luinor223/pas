package com.abclogistics.pas.workflow.grpc;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.workflow.error.AbortedException;
import com.abclogistics.pas.workflow.error.FailedPreconditionException;
import com.abclogistics.pas.workflow.domain.StepAssignee;
import com.abclogistics.pas.workflow.domain.WorkflowAction;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.grpc.CancelInstanceRequest;
import com.abclogistics.pas.workflow.grpc.CancelInstanceResponse;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentRequest;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import com.abclogistics.pas.workflow.grpc.StartInstanceRequest;
import com.abclogistics.pas.workflow.grpc.StartInstanceResponse;
import com.abclogistics.pas.workflow.grpc.StepAction;
import com.abclogistics.pas.workflow.grpc.StepInstance;
import com.abclogistics.pas.workflow.grpc.ValidateStartableRequest;
import com.abclogistics.pas.workflow.grpc.ValidateStartableResponse;
import com.abclogistics.pas.workflow.grpc.WorkflowInternalGrpc;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowActionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import com.abclogistics.pas.workflow.service.WorkflowInstanceService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@GrpcService
public class WorkflowInternalGrpcService extends WorkflowInternalGrpc.WorkflowInternalImplBase {

    private final WorkflowInstanceService instanceService;
    private final WorkflowStepInstanceRepository stepInstanceRepo;
    private final StepAssigneeRepository assigneeRepo;
    private final WorkflowActionRepository actionRepo;

    public WorkflowInternalGrpcService(WorkflowInstanceService instanceService,
                                       WorkflowStepInstanceRepository stepInstanceRepo,
                                       StepAssigneeRepository assigneeRepo,
                                       WorkflowActionRepository actionRepo) {
        this.instanceService = instanceService;
        this.stepInstanceRepo = stepInstanceRepo;
        this.assigneeRepo = assigneeRepo;
        this.actionRepo = actionRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public void validateStartable(ValidateStartableRequest request, StreamObserver<ValidateStartableResponse> responseObserver) {
        try {
            instanceService.validateStartable(request.getDocumentType());
            responseObserver.onNext(ValidateStartableResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (NotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (FailedPreconditionException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (ConflictException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional
    public void startInstance(StartInstanceRequest request, StreamObserver<StartInstanceResponse> responseObserver) {
        try {
            UUID docId = UUID.fromString(request.getDocumentId());
            UUID idemKey = UUID.fromString(request.getIdempotencyKey());
            // priority may be empty; default to NORMAL
            String priority = request.getPriority().isBlank() ? "NORMAL" : request.getPriority();
            // requested_by is string (maybe uuid string) — parse if present
            UUID requestedBy = null;
            String requestedByName = request.getRequestedBy();
            // In our service, requested_by is passed as userId? The proto says requested_by string (name?), but we treat as name.
            // Try to parse if it's a UUID; otherwise treat as name with null id.
            // For tests we need deterministic: passed requested_by as name, but we capture current user? Instead use provided value as name, id remains null or parsed.
            // Let's attempt: if request contains valid UUID in requested_by, use it; else leave null.
            try {
                if (requestedByName != null && !requestedByName.isBlank()) {
                    // heuristic: if it looks like UUID, parse
                    if (requestedByName.matches("[0-9a-fA-F-]{36}")) {
                        requestedBy = UUID.fromString(requestedByName);
                    }
                }
            } catch (Exception ignored) {}

            // customer_name not in proto, pass null or empty
            WorkflowInstance inst = instanceService.startInstance(
                    request.getDocumentType(), docId, request.getDocumentNo(),
                    "", priority, requestedBy, requestedByName, idemKey);
            responseObserver.onNext(StartInstanceResponse.newBuilder()
                    .setInstanceId(inst.getId().toString())
                    .setStatus(inst.getStatus())
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (NotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (FailedPreconditionException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (AbortedException e) {
            responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
        } catch (ConflictException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional
    public void cancelInstance(CancelInstanceRequest request, StreamObserver<CancelInstanceResponse> responseObserver) {
        try {
            UUID docId = UUID.fromString(request.getDocumentId());
            UUID idemKey = UUID.fromString(request.getIdempotencyKey());
            instanceService.cancelInstance(request.getDocumentType(), docId, idemKey);
            responseObserver.onNext(CancelInstanceResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (NotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (FailedPreconditionException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (AbortedException e) {
            responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
        } catch (ConflictException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void getInstanceByDocument(GetInstanceByDocumentRequest request, StreamObserver<GetInstanceByDocumentResponse> responseObserver) {
        try {
            UUID docId = UUID.fromString(request.getDocumentId());
            WorkflowInstance inst = instanceService.getInstanceByDocument(request.getDocumentType(), docId);
            List<WorkflowStepInstance> steps = stepInstanceRepo.findByInstance_IdOrderByStepOrderAsc(inst.getId());

            GetInstanceByDocumentResponse.Builder builder = GetInstanceByDocumentResponse.newBuilder()
                    .setInstanceId(inst.getId().toString())
                    .setStatus(inst.getStatus())
                    .setDefinitionVersionNo(inst.getDefinition().getVersionNo())
                    .setRequestedByName(inst.getRequestedByName() != null ? inst.getRequestedByName() : "")
                    .setStartedAt(inst.getCreatedAt().toString())
                    .setPriority(inst.getPriority());

            Instant now = Instant.now();
            WorkflowStepInstance current = null;
            if ("IN_PROGRESS".equals(inst.getStatus()) && inst.getCurrentStepOrder() != null) {
                current = steps.stream().filter(s -> s.getStepOrder() == inst.getCurrentStepOrder()).findFirst().orElse(null);
            }
            if (current != null) {
                builder.setCurrentStep(toProtoStep(current, now));
            }
            for (WorkflowStepInstance s : steps) {
                builder.addSteps(toProtoStep(s, now));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (NotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private StepInstance toProtoStep(WorkflowStepInstance s, Instant now) {
        StepInstance.Builder b = StepInstance.newBuilder()
                .setStepNo(s.getStepOrder())
                .setName(s.getName())
                .setApproverRole(s.getApproverRole())
                .setStatus(s.getStatus())
                .setSlaHours(s.getSlaHours())
                .setIsOverdue(isOverdue(s, now));
        if (s.getActivatedAt() != null) b.setActivatedAt(s.getActivatedAt().toString());
        List<StepAssignee> assignees = assigneeRepo.findByStepInstance_Id(s.getId());
        for (StepAssignee a : assignees) {
            b.addAssigneeNames(a.getUserName());
        }
        List<WorkflowAction> actions = actionRepo.findByStepInstance_IdOrderByCreatedAtAsc(s.getId());
        if (!actions.isEmpty()) {
            WorkflowAction a = actions.get(0); // one action per step
            StepAction.Builder ab = StepAction.newBuilder()
                    .setActorName(a.getActorName() != null ? a.getActorName() : "")
                    .setActionedAt(a.getCreatedAt().toString())
                    .setAction(a.getAction());
            if (a.getComment() != null) ab.setComment(a.getComment());
            b.setAction(ab.build());
        }
        return b.build();
    }

    private boolean isOverdue(WorkflowStepInstance s, Instant now) {
        return "ACTIVE".equals(s.getStatus()) && s.getActivatedAt() != null
                && now.isAfter(s.getActivatedAt().plusSeconds((long) s.getSlaHours() * 3600));
    }
}
