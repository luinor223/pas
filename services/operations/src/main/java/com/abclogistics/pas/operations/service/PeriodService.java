package com.abclogistics.pas.operations.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.events.DirectEventRecord;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.operations.domain.OperationPeriod;
import com.abclogistics.pas.operations.domain.PeriodCode;
import com.abclogistics.pas.operations.dto.PeriodResponse;
import com.abclogistics.pas.operations.repository.OperationPeriodRepository;
import com.abclogistics.pas.operations.repository.VolumeRecordRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class PeriodService {

    private static final Logger log = LoggerFactory.getLogger(PeriodService.class);

    private final OperationPeriodRepository periodRepo;
    private final VolumeRecordRepository volumeRepo;
    private final AuditRecorder audit;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public PeriodService(OperationPeriodRepository periodRepo, VolumeRecordRepository volumeRepo, AuditRecorder audit, KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.periodRepo = periodRepo;
        this.volumeRepo = volumeRepo;
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

        return toResponse(period, 0);
    }

    @Transactional(readOnly = true)
    public List<PeriodResponse> list() {
        Map<UUID, Long> counts = volumeRepo.countByPeriod().stream()
                .collect(java.util.stream.Collectors.toMap(
                        VolumeRecordRepository.PeriodVolumeCount::getPeriodId,
                        VolumeRecordRepository.PeriodVolumeCount::getVolumeCount));
        return periodRepo.findAllByOrderByPeriodCodeDesc().stream()
                .map(period -> toResponse(period, counts.getOrDefault(period.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public PeriodResponse get(String periodCode) {
        validatePeriodCode(periodCode);
        OperationPeriod p = periodRepo.findByPeriodCode(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));
        return toResponse(p, volumeRepo.countByPeriod_Id(p.getId()));
    }

    @Transactional
    public PeriodResponse lock(String periodCode) {
        validatePeriodCode(periodCode);
        // P0-2 fix: SELECT FOR UPDATE to serialize concurrent lock — prevents double audit/publish on READ COMMITTED
        OperationPeriod period = periodRepo.findByPeriodCodeForUpdate(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));

        if ("LOCKED".equals(period.getStatus())) {
            // idempotent — no duplicate audit, no duplicate event (lock held until commit, second waiter sees LOCKED)
            return toResponse(period, volumeRepo.countByPeriod_Id(period.getId()));
        }

        UUID actorId = SecurityUtils.currentUserId();
        String actorName = SecurityUtils.currentUser().map(u -> u.fullName()).orElse("system");

        period.lock(actorId, actorName);
        periodRepo.save(period);

        audit.record("OPERATION_PERIOD", period.getId(), periodCode, "period.locked",
                "OPEN", "LOCKED", null, Map.of("periodCode", periodCode));

        // D9 informational direct publish — must happen after commit to avoid phantom event on rollback (P0-3)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublishPeriodLocked(periodCode, actorName);
                }
            });
        } else {
            // no transaction (e.g., test without TX) — publish immediately
            doPublishPeriodLocked(periodCode, actorName);
        }

        return toResponse(period, volumeRepo.countByPeriod_Id(period.getId()));
    }

    private void doPublishPeriodLocked(String periodCode, String actorName) {
        try {
            UUID eventId = UUID.randomUUID();
            Map<String, Object> payload = new HashMap<>();
            payload.put("period_code", periodCode);
            payload.put("document_no", periodCode);
            payload.put("locked_by_name", actorName);
            payload.put("recipient_role", "ACCOUNTANT");
            // also keep aggregate_id semantics: key = period_code (business-key exception 00-registry.md:68 footnote)
            String json = objectMapper.writeValueAsString(payload);
            ProducerRecord<String, String> record = DirectEventRecord.create(
                    eventId, "operations.period_locked", "OPERATION_PERIOD", periodCode, json);
            kafka.send(record).get(5, TimeUnit.SECONDS);
            log.debug("Published operations.period_locked afterCommit for {} event_id={}", periodCode, eventId);
        } catch (Exception e) {
            // D9 informational — log but don't fail lock; event will self-heal if needed
            log.warn("Failed to publish operations.period_locked for {}: {}", periodCode, e.getMessage(), e);
        }
    }

    private void validatePeriodCode(String code) {
        if (!PeriodCode.isValid(code)) {
            throw new IllegalArgumentException("Invalid period_code, expected YYYY-MM: " + code);
        }
    }

    private PeriodResponse toResponse(OperationPeriod p, long volumeCount) {
        return new PeriodResponse(
                p.getId(),
                p.getPeriodCode(),
                p.getStartDate(),
                p.getEndDate(),
                p.getStatus(),
                volumeCount,
                p.getLockedBy(),
                p.getLockedByName(),
                p.getLockedAt(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
