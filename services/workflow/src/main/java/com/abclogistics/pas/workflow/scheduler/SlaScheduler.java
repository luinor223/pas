package com.abclogistics.pas.workflow.scheduler;

import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import com.abclogistics.pas.workflow.repository.StepAssigneeRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepInstanceRepository;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class SlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaScheduler.class);
    private final WorkflowStepInstanceRepository stepRepo;
    private final StepAssigneeRepository assigneeRepo;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaProvider;
    private final ObjectMapper objectMapper;

    public SlaScheduler(WorkflowStepInstanceRepository stepRepo, StepAssigneeRepository assigneeRepo,
                        ObjectProvider<KafkaTemplate<String, String>> kafkaProvider, ObjectMapper objectMapper) {
        this.stepRepo = stepRepo;
        this.assigneeRepo = assigneeRepo;
        this.kafkaProvider = kafkaProvider;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${workflow.sla-check-interval:PT60S}")
    @Transactional
    public void checkOverdue() {
        Instant now = Instant.now();
        List<WorkflowStepInstance> overdue = stepRepo.findByStatusAndOverdueNotifiedAtIsNullAndActivatedAtBefore("ACTIVE", now);
        for (WorkflowStepInstance step : overdue) {
            if (step.getActivatedAt() == null) continue;
            Instant deadline = step.getActivatedAt().plusSeconds((long) step.getSlaHours() * 3600);
            if (now.isAfter(deadline)) {
                try {
                    var instance = step.getInstance();
                    long waitingHours = java.time.Duration.between(step.getActivatedAt(), now).toHours();
                    var assignees = assigneeRepo.findByStepInstance_Id(step.getId());
                    List<String> assigneeIds = assignees.stream().map(a -> a.getUserId().toString()).toList();
                    Map<String, Object> payload = Map.of(
                            "instance_id", instance.getId().toString(),
                            "step_no", step.getStepOrder(),
                            "step_name", step.getName(),
                            "assignee_ids", assigneeIds,
                            "waiting_hours", waitingHours,
                            "sla_hours", step.getSlaHours(),
                            "document_no", instance.getDocumentNo()
                    );
                    String json = objectMapper.writeValueAsString(payload);
                    // D9 / registry §4: workflow.step_overdue is published WITHOUT outbox (self-heals next run), direct to pas.events
                    KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
                    if (kafka != null) {
                        ProducerRecord<String, String> record = new ProducerRecord<>("pas.events",
                                instance.getDocumentId().toString(), json);
                        record.headers().add(new RecordHeader("event_type", "workflow.step_overdue".getBytes(StandardCharsets.UTF_8)));
                        record.headers().add(new RecordHeader("document_type", instance.getDocumentTypeCode().getBytes(StandardCharsets.UTF_8)));
                        // block until acks=all ack — only stamp on success so failure self-heals next run (matches WorkflowOutboxRelay:19)
                        kafka.send(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        step.setOverdueNotifiedAt(now);
                        stepRepo.save(step);
                        log.info("SLA overdue detected for instance {} step {} (assignees={}, waiting={}h)", instance.getId(), step.getStepOrder(), assigneeIds.size(), waitingHours);
                    } else {
                        log.debug("Kafka not available, step_overdue self-heals next run for step {}", step.getId());
                        // don't stamp — retry next scheduler run
                    }
                } catch (Exception e) {
                    log.warn("Failed to emit overdue event for step {}: {}", step.getId(), e.getMessage());
                }
            }
        }
    }
}
