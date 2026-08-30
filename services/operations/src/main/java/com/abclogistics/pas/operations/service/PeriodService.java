package com.abclogistics.pas.operations.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.operations.domain.OperationPeriod;
import com.abclogistics.pas.operations.dto.PeriodResponse;
import com.abclogistics.pas.operations.repository.OperationPeriodRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class PeriodService {

    private static final Logger log = LoggerFactory.getLogger(PeriodService.class);

    private final OperationPeriodRepository periodRepo;
    private final AuditRecorder audit;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public PeriodService(OperationPeriodRepository periodRepo, AuditRecorder audit, KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.periodRepo = periodRepo;
        this.audit = audit;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PeriodResponse create(String periodCode) {
        validatePeriodCode(periodCode);
        if (periodRepo.existsByPeriodCode(periodCode)) {
            throw new ConflictException("Period already exists: " + periodCode);
        }
        YearMonth ym = YearMonth.parse(periodCode);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        UUID actor = SecurityUtils.currentUserId();
        OperationPeriod period = OperationPeriod.create(periodCode, start, end, actor);
        periodRepo.save(period);

        audit.record("OPERATION_PERIOD", period.getId(), periodCode, "period.created",
                null, "OPEN", null, Map.of("periodCode", periodCode));

        return toResponse(period);
    }

    @Transactional(readOnly = true)
    public List<PeriodResponse> list() {
        return periodRepo.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PeriodResponse get(String periodCode) {
        validatePeriodCode(periodCode);
        OperationPeriod p = periodRepo.findByPeriodCode(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));
        return toResponse(p);
    }

    @Transactional
    public PeriodResponse lock(String periodCode) {
        validatePeriodCode(periodCode);
        // P0-2 fix: SELECT FOR UPDATE to serialize concurrent lock — prevents double audit/publish on READ COMMITTED
        OperationPeriod period = periodRepo.findByPeriodCodeForUpdate(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));

        if ("LOCKED".equals(period.getStatus())) {
            // idempotent — no duplicate audit, no duplicate event (lock held until commit, second waiter sees LOCKED)
            return toResponse(period);
        }

        UUID actorId = SecurityUtils.currentUserId();
        String actorName = SecurityUtils.currentUser().map(u -> u.fullName()).orElse("system");

        period.lock(actorId, actorName);
        periodRepo.save(period);

        audit.record("OPERATION_PERIOD", period.getId(), periodCode, "period.locked",
                "OPEN", "LOCKED", null, Map.of("periodCode", periodCode));

        // D9 informational direct publish — must happen after commit to avoid phantom event on rollback (P0-3)
        // Envelope per 00-registry.md:68: event_id, event_type, occurred_at, actor_id/name, document_type/id, payload
        final UUID periodId = period.getId();
        final String periodCodeCopy = period.getPeriodCode();
        final Instant lockedAtCopy = period.getLockedAt();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublishPeriodLocked(periodId, periodCodeCopy, actorId, actorName, lockedAtCopy);
                }
            });
        } else {
            // no transaction (e.g., test without TX) — publish immediately
            doPublishPeriodLocked(periodId, periodCodeCopy, actorId, actorName, lockedAtCopy);
        }

        return toResponse(period);
    }

    private void doPublishPeriodLocked(UUID periodId, String periodCode, UUID actorId, String actorName, Instant lockedAt) {
        try {
            UUID eventId = UUID.randomUUID();
            Instant occurredAt = Instant.now();
            Map<String, Object> payload = new HashMap<>();
            payload.put("period_code", periodCode);
            payload.put("locked_by_name", actorName);
            payload.put("recipient_role", "ACCOUNTANT");
            // envelope per registry
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("event_id", eventId.toString());
            envelope.put("event_type", "operations.period_locked");
            envelope.put("occurred_at", occurredAt.toString());
            envelope.put("actor_id", actorId != null ? actorId.toString() : null);
            envelope.put("actor_name", actorName);
            envelope.put("document_type", "OPERATION_PERIOD");
            envelope.put("document_id", periodId.toString());
            envelope.put("payload", payload);
            // also keep aggregate_id semantics: key = period_code (business-key exception 00-registry.md:68 footnote)
            String json = objectMapper.writeValueAsString(envelope);
            ProducerRecord<String, String> record = new ProducerRecord<>("pas.events", periodCode, json);
            record.headers().add(new RecordHeader("event_type", "operations.period_locked".getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("document_type", "OPERATION_PERIOD".getBytes(StandardCharsets.UTF_8)));
            kafka.send(record).get(5, TimeUnit.SECONDS);
            log.debug("Published operations.period_locked afterCommit for {} event_id={}", periodCode, eventId);
        } catch (Exception e) {
            // D9 informational — log but don't fail lock; event will self-heal if needed
            log.warn("Failed to publish operations.period_locked for {}: {}", periodCode, e.getMessage(), e);
        }
    }

    private void validatePeriodCode(String code) {
        try {
            YearMonth.parse(code);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid period_code, expected YYYY-MM: " + code);
        }
    }

    private PeriodResponse toResponse(OperationPeriod p) {
        return new PeriodResponse(
                p.getId(),
                p.getPeriodCode(),
                p.getStartDate(),
                p.getEndDate(),
                p.getStatus(),
                p.getLockedBy(),
                p.getLockedByName(),
                p.getLockedAt(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
