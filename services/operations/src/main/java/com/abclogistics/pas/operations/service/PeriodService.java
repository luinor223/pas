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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
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

    public PeriodService(OperationPeriodRepository periodRepo, AuditRecorder audit, KafkaTemplate<String, String> kafka) {
        this.periodRepo = periodRepo;
        this.audit = audit;
        this.kafka = kafka;
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
        OperationPeriod p = periodRepo.findByPeriodCode(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));
        return toResponse(p);
    }

    @Transactional
    public PeriodResponse lock(String periodCode) {
        OperationPeriod period = periodRepo.findByPeriodCode(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));

        if ("LOCKED".equals(period.getStatus())) {
            // idempotent — no duplicate audit, no duplicate event
            return toResponse(period);
        }

        UUID actorId = SecurityUtils.currentUserId();
        String actorName = SecurityUtils.currentUser().map(u -> u.fullName()).orElse("system");

        period.lock(actorId, actorName);
        periodRepo.save(period);

        audit.record("OPERATION_PERIOD", period.getId(), periodCode, "period.locked",
                "OPEN", "LOCKED", null, Map.of("periodCode", periodCode));

        // direct publish operations.period_locked (D9 informational, not via outbox)
        publishPeriodLocked(period, actorName);

        return toResponse(period);
    }

    private void publishPeriodLocked(OperationPeriod period, String actorName) {
        try {
            // payload per registry: period_code, locked_by_name, recipient_role: 'ACCOUNTANT'
            String payload = String.format("{\"period_code\":\"%s\",\"locked_by_name\":\"%s\",\"recipient_role\":\"ACCOUNTANT\"}",
                    period.getPeriodCode(), actorName.replace("\"", "\\\""));
            ProducerRecord<String, String> record = new ProducerRecord<>("pas.events", period.getPeriodCode(), payload);
            record.headers().add(new RecordHeader("event_type", "operations.period_locked".getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("document_type", "OPERATION_PERIOD".getBytes(StandardCharsets.UTF_8)));
            kafka.send(record).get(5, TimeUnit.SECONDS);
            log.debug("Published operations.period_locked for {}", period.getPeriodCode());
        } catch (Exception e) {
            // D9 informational — lost event self-heals, but log and don't fail lock
            log.warn("Failed to publish operations.period_locked for {}: {}", period.getPeriodCode(), e.getMessage(), e);
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
