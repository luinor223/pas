package com.abclogistics.pas.workflow.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.identity.grpc.UserRef;
import com.abclogistics.pas.workflow.error.AbortedException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.workflow.domain.StepAssignee;
import com.abclogistics.pas.workflow.domain.WorkflowAction;
import com.abclogistics.pas.workflow.domain.WorkflowDefinition;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepDefinition;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowActionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowDefinitionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowInstanceRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepDefinitionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowInstanceService {

    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowStepDefinitionRepository stepDefRepo;
    private final WorkflowInstanceRepository instanceRepo;
    private final WorkflowStepInstanceRepository stepInstanceRepo;
    private final StepAssigneeRepository assigneeRepo;
    private final WorkflowActionRepository actionRepo;
    private final OutboxRepository outbox;
    private final IdentityGrpcClient identityClient;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public WorkflowInstanceService(WorkflowDefinitionRepository definitionRepo,
                                   WorkflowStepDefinitionRepository stepDefRepo,
                                   WorkflowInstanceRepository instanceRepo,
                                   WorkflowStepInstanceRepository stepInstanceRepo,
                                   StepAssigneeRepository assigneeRepo,
                                   WorkflowActionRepository actionRepo,
                                   OutboxRepository outbox,
                                   IdentityGrpcClient identityClient,
                                   AuditRecorder audit,
                                   ObjectMapper objectMapper) {
        this.definitionRepo = definitionRepo;
        this.stepDefRepo = stepDefRepo;
        this.instanceRepo = instanceRepo;
        this.stepInstanceRepo = stepInstanceRepo;
        this.assigneeRepo = assigneeRepo;
        this.actionRepo = actionRepo;
        this.outbox = outbox;
        this.identityClient = identityClient;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * Read-only pre-submit check: OK when every step of active definition resolves to >=1
     * ACTIVE assignee. Used by owner services before local SUBMITTED commit (ValidateStartable
     * gRPC).
     */
    @Transactional(readOnly = true)
    public void validateStartable(String documentTypeCode) {
        WorkflowDefinition active = definitionRepo.findByDocumentType_CodeAndActiveTrue(documentTypeCode)
                .orElseThrow(() -> new NotFoundException("No active workflow definition for: " + documentTypeCode));
        List<WorkflowStepDefinition> steps = stepDefRepo.findByDefinition_IdOrderByStepOrderAsc(active.getId());
        if (steps.isEmpty()) {
            throw new FailedPreconditionException("Workflow definition has no steps: " + documentTypeCode);
        }
        // distinct roles to avoid duplicate identity calls
        Map<String, List<UserRef>> roleCache = new HashMap<>();
        for (WorkflowStepDefinition step : steps) {
            String role = step.getApproverRole();
            List<UserRef> users = roleCache.computeIfAbsent(role, k -> identityClient.listUsersByRole(k));
            if (users.isEmpty()) {
                throw new FailedPreconditionException("No assignee for role: " + role + " at step " + step.getStepOrder() + " (" + step.getName() + ")");
            }
        }
    }

    @Transactional
    public WorkflowInstance startInstance(String documentTypeCode, UUID documentId, String documentNo,
                                          String customerName, String priority,
                                          UUID requestedBy, String requestedByName,
                                          UUID idempotencyKey) {
        // idempotency: same key returns existing instance regardless of status (permanent UNIQUE)
        var existingByKey = instanceRepo.findByIdempotencyKey(idempotencyKey);
        if (existingByKey.isPresent()) {
            return existingByKey.get();
        }
        // partial unique guard: different key while IN_PROGRESS -> reject
        var inProgress = instanceRepo.findInProgressByDocument(documentTypeCode, documentId);
        if (inProgress.isPresent()) {
            throw new FailedPreconditionException("Document already has an active workflow instance: " + documentId);
        }

        WorkflowDefinition active = definitionRepo.findByDocumentType_CodeAndActiveTrue(documentTypeCode)
                .orElseThrow(() -> new NotFoundException("No active workflow definition for: " + documentTypeCode));
        List<WorkflowStepDefinition> stepDefs = stepDefRepo.findByDefinition_IdOrderByStepOrderAsc(active.getId());
        if (stepDefs.isEmpty()) {
            throw new FailedPreconditionException("Workflow definition has no steps");
        }

        // Resolve assignees for whole chain upfront (empty fails immediately, whole-chain snapshot)
        Map<String, List<UserRef>> roleUsers = new HashMap<>();
        for (WorkflowStepDefinition sd : stepDefs) {
            roleUsers.computeIfAbsent(sd.getApproverRole(), k -> {
                List<UserRef> users = identityClient.listUsersByRole(k);
                if (users.isEmpty()) {
                    throw new FailedPreconditionException("No assignee for role: " + k + " at step " + sd.getStepOrder() + " (" + sd.getName() + ")");
                }
                return users;
            });
        }

        WorkflowInstance instance = WorkflowInstance.create(active, idempotencyKey, documentTypeCode, documentId, documentNo, customerName, priority, requestedBy, requestedByName);
        try {
            instanceRepo.save(instance);
            instanceRepo.flush();
        } catch (DataIntegrityViolationException e) {
            // race lost on idempotency_key or partial unique -> re-check
            var raced = instanceRepo.findByIdempotencyKey(idempotencyKey);
            if (raced.isPresent()) return raced.get();
            throw new ConflictException("Concurrent workflow instance creation conflict");
        }

        // create step instances + assignees
        List<WorkflowStepInstance> createdSteps = new ArrayList<>();
        for (WorkflowStepDefinition sd : stepDefs) {
            String status = sd.getStepOrder() == 1 ? "ACTIVE" : "PENDING";
            WorkflowStepInstance si = new WorkflowStepInstance(instance, sd.getStepOrder(), sd.getName(), sd.getApproverRole(), sd.getSlaHours(), status);
            stepInstanceRepo.save(si);
            createdSteps.add(si);
        }
        stepInstanceRepo.flush();

        for (WorkflowStepInstance si : createdSteps) {
            String role = si.getApproverRole();
            List<UserRef> users = roleUsers.get(role);
            for (UserRef u : users) {
                StepAssignee assignee = new StepAssignee(si, UUID.fromString(u.getId()), u.getFullName());
                assigneeRepo.save(assignee);
            }
        }

        // outbox events: instance_started + step_assigned for first ACTIVE step
        emit(instance.getDocumentId(), "workflow.instance_started", documentTypeCode, Map.of(
                "instance_id", instance.getId().toString(),
                "document_no", documentNo,
                "priority", instance.getPriority(),
                "document_type", documentTypeCode,
                "document_id", documentId.toString()
        ));
        // step_assigned for first step
        WorkflowStepInstance first = createdSteps.get(0);
        List<String> assigneeIds = roleUsers.get(first.getApproverRole()).stream().map(UserRef::getId).toList();
        emit(instance.getDocumentId(), "workflow.step_assigned", documentTypeCode, Map.of(
                "instance_id", instance.getId().toString(),
                "step_no", first.getStepOrder(),
                "step_name", first.getName(),
                "assignee_ids", assigneeIds,
                "document_no", documentNo,
                "customer_name", customerName != null ? customerName : ""
        ));

        audit.record("WORKFLOW_INSTANCE", instance.getId(), instance.getDocumentNo(),
                "workflow.instance_started", null, null, null,
                Map.of("documentType", documentTypeCode, "documentId", documentId.toString()));

        return instance;
    }

    @Transactional
    public void cancelInstance(String documentTypeCode, UUID documentId, UUID idempotencyKey) {
        WorkflowInstance instance = instanceRepo.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new NotFoundException("Workflow instance not found for idempotency_key: " + idempotencyKey));
        // cross-check doc identity (registry §5)
        if (!instance.getDocumentTypeCode().equals(documentTypeCode) || !instance.getDocumentId().equals(documentId)) {
            throw new FailedPreconditionException("Idempotency key does not match document");
        }
        if (!"IN_PROGRESS".equals(instance.getStatus())) {
            throw new FailedPreconditionException("Workflow instance not in progress, cannot cancel: " + instance.getStatus());
        }
        // succeed only while no step has been actioned — use exists query, not counting all rows (review: why counting every row)
        if (actionRepo.existsByStepInstance_Instance_Id(instance.getId())) {
            throw new FailedPreconditionException("Cannot cancel workflow instance after a step has been actioned");
        }
        List<WorkflowStepInstance> steps = stepInstanceRepo.findByInstance_IdOrderByStepOrderAsc(instance.getId());

        // cancel ACTIVE step via version-guarded update (close approve-vs-cancel race) — do not touch managed entity after bulk update (avoid double-update)
        for (WorkflowStepInstance s : steps) {
            if ("ACTIVE".equals(s.getStatus())) {
                int updated = stepInstanceRepo.approveIfActive(s.getId(), s.getVersion(), "CANCELLED", Instant.now(), null, null);
                if (updated == 0) {
                    throw new AbortedException("Concurrent modification during cancel — step version mismatch");
                }
            } else if ("PENDING".equals(s.getStatus())) {
                s.setStatus("CANCELLED");
                s.setCompletedAt(Instant.now());
                stepInstanceRepo.save(s);
            }
        }
        instance.setStatus("CANCELLED");
        instance.setCompletedAt(Instant.now());
        instanceRepo.save(instance);

        // No workflow.completed for CANCELLED per db-workflow.md:11 (inbox reflects state immediately) and registry §4 (only APPROVED/REJECTED/REVISION_REQUESTED)
        audit.record("WORKFLOW_INSTANCE", instance.getId(), instance.getDocumentNo(),
                "workflow.instance_cancelled", null, null, null,
                Map.of("documentType", documentTypeCode, "documentId", documentId.toString()));
    }

    @Transactional(readOnly = true)
    public WorkflowInstance getInstanceByDocument(String documentTypeCode, UUID documentId) {
        // return IN_PROGRESS if exists, else latest terminal by created_at DESC
        var inProgress = instanceRepo.findInProgressByDocument(documentTypeCode, documentId);
        if (inProgress.isPresent()) return inProgress.get();
        List<WorkflowInstance> all = instanceRepo.findByDocumentOrderByCreatedAtDesc(documentTypeCode, documentId);
        if (all.isEmpty()) throw new NotFoundException("No workflow instance for document: " + documentId);
        return all.get(0);
    }

    @Transactional
    public void actOnStep(UUID stepInstanceId, String action, String comment) {
        actOnStep(stepInstanceId, action, comment, UUID.randomUUID());
    }

    @Transactional
    public void actOnStep(UUID stepInstanceId, String action, String comment, UUID idempotencyKey) {
        // This makes sequential retries idempotent (for example, after a lost HTTP response).
        // Two requests with the same key that overlap before either commits still race through
        // approveIfActive; one succeeds and the other receives the normal concurrent-action error.
        AuthenticatedUser actor = SecurityUtils.currentUser()
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Authentication required"));
        var replay = actionRepo.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            WorkflowAction previous = replay.get();
            if (previous.getStepInstance().getId().equals(stepInstanceId)
                    && previous.getAction().equals(action)
                    && actor.userId().equals(previous.getActorId())) {
                return;
            }
            throw new FailedPreconditionException("Idempotency key was already used for a different workflow action");
        }
        WorkflowStepInstance step = stepInstanceRepo.findById(stepInstanceId)
                .orElseThrow(() -> new NotFoundException("Workflow step not found: " + stepInstanceId));
        WorkflowInstance instance = step.getInstance();

        if (!"IN_PROGRESS".equals(instance.getStatus())) {
            throw new FailedPreconditionException("Workflow instance not in progress: " + instance.getStatus());
        }
        if (!"ACTIVE".equals(step.getStatus())) {
            // D5's second predicate: status='ACTIVE' is what rejects pending/completed steps, mapped to ABORTED/FAILED_PRECONDITION
            throw new FailedPreconditionException("Step not active, cannot act: " + step.getStatus());
        }
        // APR-01 contextual auth: caller must be assignee snapshot
        boolean isAssignee = assigneeRepo.existsByStepInstance_IdAndUserId(step.getId(), actor.userId());
        if (!isAssignee) {
            throw new org.springframework.security.access.AccessDeniedException("User not assignee of this step");
        }
        // The assignee table is a candidate snapshot; current role membership remains the authority.
        if (actor.roles() == null || !actor.roles().contains(step.getApproverRole())) {
            throw new org.springframework.security.access.AccessDeniedException("User no longer holds the approver role for this step");
        }
        // APR-03 comment check
        if (!"APPROVE".equals(action) && (comment == null || comment.isBlank())) {
            throw new FailedPreconditionException("Comment required for action: " + action);
        }

        Instant now = Instant.now();
        List<WorkflowStepInstance> allSteps = stepInstanceRepo.findByInstance_IdOrderByStepOrderAsc(instance.getId());

        if ("APPROVE".equals(action)) {
            // optimistic-lock guard (D5) — bulk update already sets status/version, do not touch managed entity afterwards (avoid double-update P1#4)
            int updated = stepInstanceRepo.approveIfActive(step.getId(), step.getVersion(), "APPROVED", now, actor.userId(), actor.fullName());
            if (updated == 0) {
                throw new AbortedException("Step was concurrently modified (ABORTED)");
            }
            // record action (step reference still valid, status updated via bulk)
            WorkflowAction wa = new WorkflowAction(step, "APPROVE", actor.userId(), actor.fullName(), comment, idempotencyKey);
            actionRepo.save(wa);

            // check if last step
            boolean isLast = allSteps.stream().noneMatch(s -> s.getStepOrder() > step.getStepOrder());
            if (isLast) {
                instance.setStatus("APPROVED");
                instance.setCompletedAt(now);
                instance.setCurrentStepOrder(null);
                instanceRepo.save(instance);
                emit(instance.getDocumentId(), "workflow.completed", instance.getDocumentTypeCode(), Map.of(
                        "instance_id", instance.getId().toString(),
                        "outcome", "APPROVED",
                        "document_no", instance.getDocumentNo(),
                        "requested_by", instance.getRequestedBy() != null ? instance.getRequestedBy().toString() : "",
                        "requested_by_name", instance.getRequestedByName() != null ? instance.getRequestedByName() : ""
                ));
            } else {
                // activate next step
                int nextOrder = step.getStepOrder() + 1;
                WorkflowStepInstance next = allSteps.stream().filter(s -> s.getStepOrder() == nextOrder).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Next step not found"));
                next.setStatus("ACTIVE");
                next.setActivatedAt(now);
                stepInstanceRepo.save(next);
                instance.setCurrentStepOrder(nextOrder);
                instanceRepo.save(instance);
                emit(instance.getDocumentId(), "workflow.step_actioned", instance.getDocumentTypeCode(), Map.of(
                        "instance_id", instance.getId().toString(),
                        "step_no", step.getStepOrder(),
                        "action", action,
                        "comment", comment != null ? comment : "",
                        "document_no", instance.getDocumentNo(),
                        "requested_by", instance.getRequestedBy() != null ? instance.getRequestedBy().toString() : "",
                        "requested_by_name", instance.getRequestedByName() != null ? instance.getRequestedByName() : ""
                ));
                // step_assigned for next
                List<StepAssignee> nextAssignees = assigneeRepo.findByStepInstance_Id(next.getId());
                List<String> assigneeIds = nextAssignees.stream().map(a -> a.getUserId().toString()).toList();
                emit(instance.getDocumentId(), "workflow.step_assigned", instance.getDocumentTypeCode(), Map.of(
                        "instance_id", instance.getId().toString(),
                        "step_no", next.getStepOrder(),
                        "step_name", next.getName(),
                        "assignee_ids", assigneeIds,
                        "document_no", instance.getDocumentNo(),
                        "customer_name", instance.getCustomerName() != null ? instance.getCustomerName() : ""
                ));
            }
            audit.record("WORKFLOW_STEP", step.getId(), instance.getDocumentNo(),
                    "workflow.step_approved", null, null, null,
                    workflowAuditChanges(instance, step));

        } else if ("REJECT".equals(action) || "REQUEST_REVISION".equals(action)) {
            // merged: REJECT and REQUEST_REVISION share same flow, only status/audit differ (review)
            String newStatus = "REJECT".equals(action) ? "REJECTED" : "REVISION_REQUESTED";
            String auditName = "REJECT".equals(action) ? "workflow.step_rejected" : "workflow.step_revision_requested";
            int updated = stepInstanceRepo.approveIfActive(step.getId(), step.getVersion(), newStatus, now, actor.userId(), actor.fullName());
            if (updated == 0) throw new AbortedException("Step concurrently modified");
            WorkflowAction wa = new WorkflowAction(step, action, actor.userId(), actor.fullName(), comment, idempotencyKey);
            actionRepo.save(wa);
            instance.setStatus(newStatus);
            instance.setCompletedAt(now);
            instance.setCurrentStepOrder(null);
            instanceRepo.save(instance);
            for (WorkflowStepInstance s : allSteps) {
                if ("PENDING".equals(s.getStatus())) {
                    s.setStatus("CANCELLED");
                    s.setCompletedAt(now);
                    stepInstanceRepo.save(s);
                }
            }
            emit(instance.getDocumentId(), "workflow.completed", instance.getDocumentTypeCode(), Map.of(
                    "instance_id", instance.getId().toString(),
                    "outcome", newStatus,
                    "document_no", instance.getDocumentNo(),
                    "requested_by", instance.getRequestedBy() != null ? instance.getRequestedBy().toString() : "",
                    "requested_by_name", instance.getRequestedByName() != null ? instance.getRequestedByName() : ""
            ));
            emit(instance.getDocumentId(), "workflow.step_actioned", instance.getDocumentTypeCode(), Map.of(
                    "instance_id", instance.getId().toString(),
                    "step_no", step.getStepOrder(),
                    "action", action,
                    "comment", comment,
                    "document_no", instance.getDocumentNo(),
                    "requested_by", instance.getRequestedBy() != null ? instance.getRequestedBy().toString() : "",
                    "requested_by_name", instance.getRequestedByName() != null ? instance.getRequestedByName() : ""
            ));
            audit.record("WORKFLOW_STEP", step.getId(), instance.getDocumentNo(), auditName,
                    null, null, null,
                    workflowAuditChanges(instance, step));
        }
    }

    private static Map<String, Object> workflowAuditChanges(WorkflowInstance instance,
                                                             WorkflowStepInstance step) {
        return Map.of(
                "instanceId", instance.getId().toString(),
                "stepOrder", step.getStepOrder(),
                "documentType", instance.getDocumentTypeCode(),
                "documentId", instance.getDocumentId().toString());
    }

    private void emit(UUID aggregateId, String eventType, String aggregateType, Map<String, Object> payloadMap) {
        try {
            String json = objectMapper.writeValueAsString(payloadMap);
            OutboxEvent event = OutboxEvent.event(eventType, aggregateType, aggregateId, json);
            outbox.save(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
