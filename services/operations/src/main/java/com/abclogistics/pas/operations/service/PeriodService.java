package com.abclogistics.pas.operations.service;

import com.abclogistics.pas.operations.domain.OperationPeriod;
import com.abclogistics.pas.operations.dto.*;
import com.abclogistics.pas.operations.repository.OperationPeriodRepository;
import com.abclogistics.pas.operations.repository.VolumeRecordRepository;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PeriodService {

    private final OperationPeriodRepository periodRepository;
    private final VolumeRecordRepository volumeRepository;
    private final OutboxRepository outboxRepository;

    PeriodService(OperationPeriodRepository periodRepository,
                  VolumeRecordRepository volumeRepository,
                  OutboxRepository outboxRepository) {
        this.periodRepository = periodRepository;
        this.volumeRepository = volumeRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<PeriodResponse> list(int page, int size) {
        Page<OperationPeriod> periods = periodRepository.findAllSorted(PageRequest.of(page, size));
        List<PeriodResponse> data = periods.getContent().stream()
            .map(this::toResponse)
            .toList();
        return new PageResponse<>(data, new Meta(page, size));
    }

    @Transactional
    public PeriodResponse create(CreatePeriodRequest req) {
        if (periodRepository.existsByPeriodCode(req.periodCode())) {
            throw new IllegalArgumentException("Period code already exists: " + req.periodCode());
        }
        OperationPeriod period = new OperationPeriod();
        period.setPeriodCode(req.periodCode());
        period.setPeriodName(req.periodName());
        period.setMonth(req.month());
        period.setYear(req.year());
        period.setStartDate(LocalDate.parse(req.startDate()));
        period.setEndDate(LocalDate.parse(req.endDate()));
        period.setStatus(OperationPeriod.PeriodStatus.DRAFT);
        periodRepository.save(period);
        return toResponse(period);
    }

    @Transactional
    public PeriodResponse lock(String periodCode) {
        OperationPeriod period = periodRepository.findByPeriodCode(periodCode)
            .orElseThrow(() -> new IllegalArgumentException("Period not found: " + periodCode));
        if (period.isLocked()) {
            throw new IllegalStateException("Period already locked: " + periodCode);
        }
        period.setStatus(OperationPeriod.PeriodStatus.LOCKED);
        periodRepository.save(period);

        OutboxEvent event = OutboxEvent.event(
            "operations.period_locked",
            "OperationPeriod",
            UUID.randomUUID(),
            String.format("{\"periodCode\":\"%s\",\"lockedAt\":\"%s\"}",
                periodCode, LocalDateTime.now())
        );
        outboxRepository.save(event);

        return toResponse(period);
    }

    private PeriodResponse toResponse(OperationPeriod p) {
        return new PeriodResponse(
            p.getId(),
            p.getPeriodCode(),
            p.getPeriodName(),
            p.getMonth(),
            p.getYear(),
            p.getStartDate().toString(),
            p.getEndDate().toString(),
            p.getStatus().name(),
            volumeRepository.countByPeriodCode(p.getPeriodCode())
        );
    }
}
