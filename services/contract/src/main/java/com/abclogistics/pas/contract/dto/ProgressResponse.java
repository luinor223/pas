package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.service.ContractService.ApprovalProgress;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import com.abclogistics.pas.workflow.grpc.StepAction;
import com.abclogistics.pas.workflow.grpc.StepInstance;

import java.util.List;
import java.util.UUID;

/** Approval progress (4.7) as REST sees it; proto3 empty strings are normalised back to null. */
public record ProgressResponse(
        String documentStatus,
        String workflowState,
        UUID instanceId,
        Integer definitionVersionNo,
        String requestedByName,
        String startedAt,
        String priority,
        Step currentStep,
        List<Step> steps) {

    public record Step(
            int stepNo,
            String name,
            String approverRole,
            String status,
            List<String> assigneeNames,
            Action action,
            String activatedAt,
            Integer slaHours,
            boolean overdue) { }

    public record Action(String actorName, String actionedAt, String comment, String action) { }

    public static ProgressResponse of(ApprovalProgress progress) {
        GetInstanceByDocumentResponse instance = progress.instance();
        if (instance == null) {
            return new ProgressResponse(progress.documentStatus().name(), progress.state(),
                    null, null, null, null, null, null, List.of());
        }
        return new ProgressResponse(
                progress.documentStatus().name(),
                progress.state(),
                parseUuid(instance.getInstanceId()),
                instance.getDefinitionVersionNo() == 0 ? null : instance.getDefinitionVersionNo(),
                text(instance.getRequestedByName()),
                text(instance.getStartedAt()),
                text(instance.getPriority()),
                // proto3 has no "has" bit: an unset current_step reads back as a default instance
                instance.hasCurrentStep() ? step(instance.getCurrentStep()) : null,
                instance.getStepsList().stream().map(ProgressResponse::step).toList());
    }

    private static Step step(StepInstance step) {
        return new Step(
                step.getStepNo(),
                text(step.getName()),
                text(step.getApproverRole()),
                text(step.getStatus()),
                List.copyOf(step.getAssigneeNamesList()),
                step.hasAction() ? action(step.getAction()) : null,
                text(step.getActivatedAt()),
                step.getSlaHours() == 0 ? null : step.getSlaHours(),
                step.getIsOverdue());
    }

    private static Action action(StepAction action) {
        return new Action(text(action.getActorName()), text(action.getActionedAt()),
                text(action.getComment()), text(action.getAction()));
    }

    private static String text(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static UUID parseUuid(String value) {
        return text(value) == null ? null : UUID.fromString(value);
    }
}
