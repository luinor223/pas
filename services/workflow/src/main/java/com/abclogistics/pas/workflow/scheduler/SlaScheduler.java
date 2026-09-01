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
import java.util.UUID;

@Component
public class SlaScheduler {

    static final String EVENT_TYPE = "workflow.step_overdue";

    private static final Logger log = LoggerFactory.getLogger(SlaScheduler.class);
    private final StepAssigneeRepository assigneeRepo;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaProvider;
    private final ObjectMapper objectMapper;
    private final SlaSchedulerHelper helper;

    public SlaScheduler(StepAssigneeRepository assigneeRepo,
                        ObjectProvider<KafkaTemplate<String, String>> kafkaProvider,
                        ObjectMapper objectMapper,
                        SlaSchedulerHelper helper) {
        this.assigneeRepo = assigneeRepo;
        this.kafkaProvider = kafkaProvider;
        this.objectMapper = objectMapper;
        this.helper = helper;
    }

    @Scheduled(fixedDelayString = "${workflow.sla-check-interval:PT60S}")
    public void checkOverdue() {
        // Fetch outside TX to avoid holding DB connection while blocking on Kafka acks (P3 residual) — via helper so @Transactional proxy applies
        List<WorkflowStepInstance> overdue = helper.fetchOverdueCandidates();
        Instant now = Instant.now();
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
                    KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
                    if (kafka != null) {
                        ProducerRecord<String, String> record = new ProducerRecord<>("pas.events",
                                instance.getDocumentId().toString(), json);
                        record.headers().add(new RecordHeader("event_id", eventId(step.getId(), deadline).toString().getBytes(StandardCharsets.UTF_8)));
                        record.headers().add(new RecordHeader("event_type", EVENT_TYPE.getBytes(StandardCharsets.UTF_8)));
                        record.headers().add(new RecordHeader("document_type", instance.getDocumentTypeCode().getBytes(StandardCharsets.UTF_8)));
                        // block until acks=all ack — only stamp on success so failure self-heals next run (matches WorkflowOutboxRelay:19)
                        kafka.send(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        helper.markOverdueNotified(step.getId(), now);
                        log.info("SLA overdue detected for instance {} step {} (assignees={}, waiting={}h)", instance.getId(), step.getStepOrder(), assigneeIds.size(), waitingHours);
                    } else {
                        log.debug("Kafka not available, step_overdue self-heals next run for step {}", step.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to emit overdue event for step {}: {}", step.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Derived, not random — the same reasoning that gave {@code document.expiring} its id
     * (registry §4 change log). This event has no outbox row, and the ack and the
     * {@code overdue_notified_at} stamp cannot be made atomic: a crash between them re-sends, and
     * two replicas sweeping at once can both send before either stamps. A fresh uuid would make
     * each of those a new event, so notification-service could not dedupe it and would nag twice.
     *
     * <p>The deadline is part of the key deliberately — a step whose SLA was extended is overdue
     * against a different deadline, which is a genuinely new warning and earns a new id.
     */
    static UUID eventId(UUID stepInstanceId, Instant deadline) {
        String name = "%s:%s:%s".formatted(EVENT_TYPE, stepInstanceId, deadline);
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
