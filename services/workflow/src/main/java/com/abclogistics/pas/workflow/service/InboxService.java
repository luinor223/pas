package com.abclogistics.pas.workflow.service;

import com.abclogistics.pas.workflow.dto.InboxResponse;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowActionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Locale;

@Service
public class InboxService {

    private final WorkflowInstanceRepository instanceRepo;
    private final StepAssigneeRepository assigneeRepo;
    private final WorkflowActionRepository actionRepo;

    public InboxService(WorkflowInstanceRepository instanceRepo,
                        StepAssigneeRepository assigneeRepo,
                        WorkflowActionRepository actionRepo) {
        this.instanceRepo = instanceRepo;
        this.assigneeRepo = assigneeRepo;
        this.actionRepo = actionRepo;
    }

    @Transactional(readOnly = true)
    public InboxResponse assignedToMe(UUID userId, int page, int size, String q, String documentType, String priority) {
        var assignees = assigneeRepo.findInboxPage(userId, searchTerm(q), enumFilter(documentType), enumFilter(priority), PageRequest.of(page, size));
        List<InboxResponse.InboxItem> items = new ArrayList<>();
        for (var sa : assignees.getContent()) {
            WorkflowStepInstance step = sa.getStepInstance();
            WorkflowInstance inst = step.getInstance();
            items.add(toItem(inst, step));
        }
        return response(items, assignees);
    }

    @Transactional(readOnly = true)
    public InboxResponse submittedByMe(UUID userId, int page, int size, String q, String documentType, String priority) {
        var rows = instanceRepo.findSubmittedInboxPage(
                userId, searchTerm(q), enumFilter(documentType), enumFilter(priority), PageRequest.of(page, size));
        List<InboxResponse.InboxItem> items = rows.getContent().stream()
                .map(row -> toItem(row.getInstance(), row.getStep()))
                .toList();
        return response(items, rows);
    }

    @Transactional(readOnly = true)
    public InboxResponse completed(UUID userId, int page, int size, String q, String documentType, String priority) {
        var actions = actionRepo.findCompletedInboxPage(
                userId, searchTerm(q), enumFilter(documentType), enumFilter(priority), PageRequest.of(page, size));
        List<InboxResponse.InboxItem> items = new ArrayList<>();
        for (var action : actions.getContent()) {
            WorkflowStepInstance step = action.getStepInstance();
            WorkflowInstance inst = step.getInstance();
            items.add(toItem(inst, step));
        }
        return response(items, actions);
    }

    private InboxResponse response(List<InboxResponse.InboxItem> items, Page<?> result) {
        return new InboxResponse(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private String searchTerm(String value) {
        String normalized = filter(value);
        return normalized == null ? null : "%" + normalized.toLowerCase(Locale.ROOT) + "%";
    }

    private String filter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String enumFilter(String value) {
        String normalized = filter(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private InboxResponse.InboxItem toItem(WorkflowInstance inst, WorkflowStepInstance step) {
        return new InboxResponse.InboxItem(
                inst.getId(),
                step != null ? step.getId() : null,
                inst.getDocumentTypeCode(),
                inst.getDocumentId(),
                inst.getDocumentNo(),
                inst.getCustomerName(),
                inst.getStatus(),
                inst.getPriority(),
                inst.getCurrentStepOrder() != null ? inst.getCurrentStepOrder() : 0,
                step != null ? step.getName() : null,
                step != null ? step.getApproverRole() : null,
                step != null ? step.getActivatedAt() : null,
                inst.getCreatedAt(),
                inst.getRequestedBy(),
                inst.getRequestedByName()
        );
    }
}
