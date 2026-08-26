package com.abclogistics.pas.workflow.service;

import com.abclogistics.pas.workflow.dto.InboxResponse;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowActionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowInstanceRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class InboxService {

    private final WorkflowInstanceRepository instanceRepo;
    private final WorkflowStepInstanceRepository stepInstanceRepo;
    private final StepAssigneeRepository assigneeRepo;
    private final WorkflowActionRepository actionRepo;

    public InboxService(WorkflowInstanceRepository instanceRepo,
                        WorkflowStepInstanceRepository stepInstanceRepo,
                        StepAssigneeRepository assigneeRepo,
                        WorkflowActionRepository actionRepo) {
        this.instanceRepo = instanceRepo;
        this.stepInstanceRepo = stepInstanceRepo;
        this.assigneeRepo = assigneeRepo;
        this.actionRepo = actionRepo;
    }

    @Transactional(readOnly = true)
    public InboxResponse assignedToMe(UUID userId) {
        // JOIN FETCH avoids N+1 (P2#10)
        List<com.abclogistics.pas.workflow.domain.StepAssignee> assignees = assigneeRepo.findActiveWithFetchByUserId(userId);
        List<InboxResponse.InboxItem> items = new ArrayList<>();
        for (var sa : assignees) {
            WorkflowStepInstance step = sa.getStepInstance();
            WorkflowInstance inst = step.getInstance();
            items.add(toItem(inst, step));
        }
        return new InboxResponse(items);
    }

    @Transactional(readOnly = true)
    public InboxResponse submittedByMe(UUID userId) {
        List<WorkflowInstance> instances = instanceRepo.findByRequestedBy(userId);
        List<InboxResponse.InboxItem> items = instances.stream().map(inst -> {
            // find current active step if any
            WorkflowStepInstance current = null;
            if (inst.getCurrentStepOrder() != null) {
                current = stepInstanceRepo.findByInstance_IdAndStepOrder(inst.getId(), inst.getCurrentStepOrder()).orElse(null);
            }
            return toItem(inst, current);
        }).toList();
        return new InboxResponse(items);
    }

    @Transactional(readOnly = true)
    public InboxResponse completed(UUID userId) {
        var actions = actionRepo.findByActorIdOrderByCreatedAtDesc(userId);
        // deduplicate by instance? But each action is a separate item; group by step instance's instance
        List<InboxResponse.InboxItem> items = new ArrayList<>();
        for (var action : actions) {
            WorkflowStepInstance step = action.getStepInstance();
            WorkflowInstance inst = step.getInstance();
            items.add(toItem(inst, step));
        }
        return new InboxResponse(items);
    }

    private InboxResponse.InboxItem toItem(WorkflowInstance inst, WorkflowStepInstance step) {
        return new InboxResponse.InboxItem(
                inst.getId(),
                inst.getDocumentTypeCode(),
                inst.getDocumentId(),
                inst.getDocumentNo(),
                inst.getCustomerName(),
                inst.getStatus(),
                inst.getPriority(),
                inst.getCurrentStepOrder() != null ? inst.getCurrentStepOrder() : 0,
                step != null ? step.getName() : null,
                step != null ? step.getApproverRole() : null,
                inst.getCreatedAt(),
                inst.getRequestedBy(),
                inst.getRequestedByName()
        );
    }
}
