package com.abclogistics.pas.workflow.scheduler;

import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class SlaSchedulerHelper {

    private final WorkflowStepInstanceRepository stepRepo;

    public SlaSchedulerHelper(WorkflowStepInstanceRepository stepRepo) {
        this.stepRepo = stepRepo;
    }

    @Transactional(readOnly = true)
    public List<WorkflowStepInstance> fetchOverdueCandidates() {
        return stepRepo.findByStatusAndOverdueNotifiedAtIsNullAndActivatedAtBefore("ACTIVE", Instant.now());
    }

    @Transactional
    public void markOverdueNotified(UUID stepId, Instant when) {
        stepRepo.findById(stepId).ifPresent(s -> {
            if (s.getOverdueNotifiedAt() == null) {
                s.setOverdueNotifiedAt(when);
                stepRepo.save(s);
            }
        });
    }
}
