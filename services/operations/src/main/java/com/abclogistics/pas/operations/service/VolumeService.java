package com.abclogistics.pas.operations.service;

import com.abclogistics.pas.operations.domain.OperationPeriod;
import com.abclogistics.pas.operations.domain.VolumeRecord;
import com.abclogistics.pas.operations.dto.*;
import com.abclogistics.pas.operations.repository.OperationPeriodRepository;
import com.abclogistics.pas.operations.repository.VolumeRecordRepository;
import com.abclogistics.pas.common.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class VolumeService {

    private final VolumeRecordRepository volumeRepository;
    private final OperationPeriodRepository periodRepository;

    VolumeService(VolumeRecordRepository volumeRepository, OperationPeriodRepository periodRepository) {
        this.volumeRepository = volumeRepository;
        this.periodRepository = periodRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<VolumeResponse> list(String periodCode, int page, int size) {
        Page<VolumeRecord> volumes = volumeRepository.findByPeriodCode(periodCode, PageRequest.of(page, size));
        List<VolumeResponse> data = volumes.getContent().stream()
            .map(this::toResponse)
            .toList();
        return new PageResponse<>(data, new Meta(page, size));
    }

    @Transactional(readOnly = true)
    public List<VolumeResponse> listByContract(String periodCode, String contractCode) {
        return volumeRepository.findByPeriodCodeAndContractCode(periodCode, contractCode).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public VolumeResponse create(String periodCode, CreateVolumeRequest req) {
        OperationPeriod period = periodRepository.findByPeriodCode(periodCode)
            .orElseThrow(() -> new IllegalArgumentException("Period not found: " + periodCode));

        if (period.isLocked()) {
            boolean hasPermission = SecurityUtils.hasPermission("volume:edit_locked");
            if (!hasPermission) {
                throw new IllegalStateException("Period is locked. Permission volume:edit_locked required.");
            }
        }

        BigDecimal volumeCost = req.quantity().multiply(req.unitPrice());

        VolumeRecord record = new VolumeRecord();
        record.setPeriodCode(periodCode);
        record.setContractId(req.contractId());
        record.setContractCode(req.contractCode());
        record.setContractName(req.contractName());
        record.setPartnerId(req.partnerId());
        record.setPartnerName(req.partnerName());
        record.setServiceItemId(req.serviceItemId());
        record.setServiceCode(req.serviceCode());
        record.setServiceName(req.serviceName());
        record.setQuantity(req.quantity());
        record.setUnit(req.unit());
        record.setUnitPrice(req.unitPrice());
        record.setVolumeCostAmount(volumeCost);
        record.setNote(req.note());
        volumeRepository.save(record);
        return toResponse(record);
    }

    @Transactional
    public VolumeResponse update(Long id, String periodCode, UpdateVolumeRequest req) {
        VolumeRecord record = volumeRepository.findByIdAndPeriodCode(id, periodCode)
            .orElseThrow(() -> new IllegalArgumentException("Volume record not found: " + id));

        OperationPeriod period = periodRepository.findByPeriodCode(periodCode)
            .orElseThrow(() -> new IllegalArgumentException("Period not found: " + periodCode));

        if (period.isLocked()) {
            boolean hasPermission = SecurityUtils.hasPermission("volume:edit_locked");
            if (!hasPermission) {
                throw new IllegalStateException("Period is locked. Permission volume:edit_locked required.");
            }
        }

        if (req.quantity() != null) record.setQuantity(req.quantity());
        if (req.unit() != null) record.setUnit(req.unit());
        if (req.unitPrice() != null) record.setUnitPrice(req.unitPrice());
        if (req.note() != null) record.setNote(req.note());

        record.setVolumeCostAmount(record.getQuantity().multiply(record.getUnitPrice()));
        volumeRepository.save(record);
        return toResponse(record);
    }

    @Transactional
    public void delete(Long id, String periodCode) {
        VolumeRecord record = volumeRepository.findByIdAndPeriodCode(id, periodCode)
            .orElseThrow(() -> new IllegalArgumentException("Volume record not found: " + id));

        OperationPeriod period = periodRepository.findByPeriodCode(periodCode)
            .orElseThrow(() -> new IllegalArgumentException("Period not found: " + periodCode));

        if (period.isLocked()) {
            boolean hasPermission = SecurityUtils.hasPermission("volume:edit_locked");
            if (!hasPermission) {
                throw new IllegalStateException("Period is locked. Permission volume:edit_locked required.");
            }
        }

        volumeRepository.delete(record);
    }

    private VolumeResponse toResponse(VolumeRecord vr) {
        return new VolumeResponse(
            vr.getId(),
            vr.getPeriodCode(),
            vr.getContractId(),
            vr.getContractCode(),
            vr.getContractName(),
            vr.getPartnerId(),
            vr.getPartnerName(),
            vr.getServiceItemId(),
            vr.getServiceCode(),
            vr.getServiceName(),
            vr.getQuantity(),
            vr.getUnit(),
            vr.getUnitPrice(),
            vr.getVolumeCostAmount(),
            vr.getNote()
        );
    }
}
