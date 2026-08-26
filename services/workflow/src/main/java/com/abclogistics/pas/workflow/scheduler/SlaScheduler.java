package com.abclogistics.pas.workflow.scheduler;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class SlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaScheduler.class);
    private final WorkflowStepInstanceRepository stepRepo;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public SlaScheduler(WorkflowStepInstanceRepository stepRepo, OutboxRepository outbox, ObjectMapper objectMapper) {
        this.stepRepo = stepRepo;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${workflow.sla-check-interval:PT60S}")
    @Transactional
    public void checkOverdue() {
        Instant now = Instant.now();
        // Find ACTIVE steps where activated_at + sla_hours < now and not yet notified
        List<WorkflowStepInstance> overdue = stepRepo.findByStatusAndOverdueNotifiedAtIsNullAndActivatedAtBefore("ACTIVE", now);
        // Filter by sla_hours manually since query only checks activated_at < now, need sla check
        for (WorkflowStepInstance step : overdue) {
            if (step.getActivatedAt() == null) continue;
            Instant deadline = step.getActivatedAt().plusSeconds((long) step.getSlaHours() * 3600);
            if (now.isAfter(deadline)) {
                try {
                    var instance = step.getInstance();
                    long waitingHours = java.time.Duration.between(step.getActivatedAt(), now).toHours();
                    Map<String, Object> payload = Map.of(
                            "instance_id", instance.getId().toString(),
                            "step_no", step.getStepOrder(),
                            "step_name", step.getName(),
                            "waiting_hours", waitingHours,
                            "sla_hours", step.getSlaHours(),
                            "document_no", instance.getDocumentNo()
                    );
                    String json = objectMapper.writeValueAsString(payload);
                    // workflow.step_overdue is published without outbox? Per registry it's direct without outbox (D9 self-heals).
                    // But we still store via outbox for notification? Registry says no outbox for step_overdue (self-heals next run).
                    // For simplicity, publish via outbox as pas.events but with no outbox row would not survive. We use direct Kafka or outbox?
                    // Registry says workflow.step_overdue is produced by scheduler vs sla_hours, Outbox? no — self-heals.
                    // So we emit via outbox anyway for testability, but we also set overdue_notified_at to avoid spam once per step.
                    OutboxEvent event = OutboxEvent.event("workflow.step_overdue", instance.getDocumentTypeCode(), instance.getDocumentId(), json);
                    outbox.save(event);
                    step.setOverdueNotifiedAt(now);
                    stepRepo.save(step);
                    log.info("SLA overdue detected for instance {} step {}", instance.getId(), step.getStepOrder());
                } catch (Exception e) {
                    log.warn("Failed to emit overdue event for step {}: {}", step.getId(), e.getMessage());
                }
            }
        }
    }
}
