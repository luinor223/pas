package com.abclogistics.pas.workflow;

import com.abclogistics.pas.workflow.domain.StepAssignee;
import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowActionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowInstanceRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.abclogistics.pas.workflow.service.InboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

    @Mock WorkflowInstanceRepository instances;
    @Mock StepAssigneeRepository assignees;
    @Mock WorkflowActionRepository actions;

    @Test
    void assignedItemExposesTheStepIdRequiredByTheActionEndpoint() {
        UUID userId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        WorkflowInstance instance = WorkflowInstance.create(
                null, UUID.randomUUID(), "CONTRACT", UUID.randomUUID(),
                "CTR-2026-0012", "Saigon Port Services", "HIGH", userId, "Submitter");
        org.springframework.test.util.ReflectionTestUtils.setField(instance, "id", instanceId);
        WorkflowStepInstance step = new WorkflowStepInstance(
                instance, 1, "Legal review", "LEGAL_REVIEWER", 48, "ACTIVE");
        ReflectionTestUtils.setField(step, "id", stepId);
        StepAssignee assignee = new StepAssignee(step, userId, "Reviewer");
        when(assignees.findInboxPage(userId, null, null, null, PageRequest.of(0, 15)))
                .thenReturn(new PageImpl<>(List.of(assignee), PageRequest.of(0, 15), 1));

        var item = new InboxService(instances, assignees, actions)
                .assignedToMe(userId, 0, 15, null, null, null).items().getFirst();

        assertThat(item.instanceId()).isEqualTo(instanceId);
        assertThat(item.stepInstanceId()).isEqualTo(stepId);
        assertThat(item.stepActivatedAt()).isNotNull();
    }
}
