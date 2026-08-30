package com.abclogistics.pas.operations.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.operations.domain.OperationPeriod;
import com.abclogistics.pas.operations.domain.VolumeRecord;
import com.abclogistics.pas.operations.dto.VolumeResponse;
import com.abclogistics.pas.operations.grpc.ContractGrpcClient;
import com.abclogistics.pas.operations.grpc.PricingGrpcClient;
import com.abclogistics.pas.operations.repository.OperationPeriodRepository;
import com.abclogistics.pas.operations.repository.VolumeRecordRepository;
import com.abclogistics.pas.pricing.grpc.GetServiceItemResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VolumeService {

    private final OperationPeriodRepository periodRepo;
    private final VolumeRecordRepository volumeRepo;
    private final ContractGrpcClient contractClient;
    private final PricingGrpcClient pricingClient;
    private final AuditRecorder audit;

    public VolumeService(OperationPeriodRepository periodRepo,
                         VolumeRecordRepository volumeRepo,
                         ContractGrpcClient contractClient,
                         PricingGrpcClient pricingClient,
                         AuditRecorder audit) {
        this.periodRepo = periodRepo;
        this.volumeRepo = volumeRepo;
        this.contractClient = contractClient;
        this.pricingClient = pricingClient;
        this.audit = audit;
    }

    @Transactional
    public VolumeResponse create(UUID contractId, String periodCode, String serviceCode, BigDecimal quantity, String note) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
        OperationPeriod period = periodRepo.findByPeriodCode(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));

        // guard: if period LOCKED, need volume:write? Actually creation after lock should also require edit_locked
        // Business rule: adjust before lock; after lock need special permission. Creation is also an edit.
        if (period.isLocked() && !hasPermission("volume:edit_locked")) {
            throw new AccessDeniedException("Period is locked; volume:edit_locked required");
        }

        // validate service_code via pricing (snapshots unit/service_name, fail fast if not found)
        GetServiceItemResponse serviceItem = pricingClient.getServiceItem(serviceCode);
        if (!serviceItem.getIsActive()) {
            throw new com.abclogistics.pas.operations.error.FailedPreconditionException("Service item not active: " + serviceCode);
        }
        String serviceName = serviceItem.getName();
        String unit = serviceItem.getUnit();

        // validate contract and snapshot customer_name/customer_id
        GetContractResponse contract = contractClient.getContract(contractId);
        String customerName = contract.getCustomerName();
        UUID customerId;
        try {
            customerId = UUID.fromString(contract.getCustomerId());
        } catch (Exception e) {
            customerId = null;
        }

        String recordNo = generateRecordNo(periodCode);

        UUID actor = SecurityUtils.currentUserId();
        VolumeRecord record = VolumeRecord.create(
                period, recordNo, contractId, customerId, customerName,
                serviceCode, serviceName, unit, quantity, note, actor);
        volumeRepo.save(record);

        Map<String, Object> changes = Map.of(
                "contractId", contractId.toString(),
                "periodCode", periodCode,
                "serviceCode", serviceCode,
                "quantity", quantity.toPlainString()
        );
        // mandatory audit for every volume create, especially post-lock path
        audit.record("VOLUME_RECORD", record.getId(), recordNo, "volume.created",
                null, null, null, changes);

        return toResponse(record);
    }

    @Transactional(readOnly = true)
    public List<VolumeResponse> list(String periodCode, UUID contractId) {
        List<VolumeRecord> records;
        if (periodCode != null && contractId != null) {
            records = volumeRepo.findByContractIdAndPeriod_PeriodCode(contractId, periodCode);
        } else if (periodCode != null) {
            records = volumeRepo.findByPeriod_PeriodCode(periodCode);
        } else {
            records = volumeRepo.findAll();
        }
        return records.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public VolumeResponse get(UUID id) {
        VolumeRecord r = volumeRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Volume record not found: " + id));
        return toResponse(r);
    }

    @Transactional
    public VolumeResponse update(UUID id, BigDecimal quantity, String note) {
        VolumeRecord record = volumeRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Volume record not found: " + id));

        OperationPeriod period = record.getPeriod();
        // guard OPEN or volume:edit_locked + audit
        if (period.isLocked() && !hasPermission("volume:edit_locked")) {
            throw new AccessDeniedException("Period is locked; volume:edit_locked required to edit");
        }

        BigDecimal oldQty = record.getQuantity();
        record.updateQuantity(quantity, note, SecurityUtils.currentUserId());
        volumeRepo.save(record);

        // mandatory audit for edit, especially post-lock edits (4.5 quyền đặc biệt trace)
        Map<String, Object> changes = Map.of(
                "oldQuantity", oldQty.toPlainString(),
                "newQuantity", quantity.toPlainString(),
                "periodLocked", period.isLocked()
        );
        audit.record("VOLUME_RECORD", record.getId(), record.getRecordNo(), "volume.updated",
                null, null, null, changes);

        return toResponse(record);
    }

    private String generateRecordNo(String periodCode) {
        String year = periodCode.substring(0, 4);
        String prefix = "VOL-" + year + "-";
        long count = volumeRepo.countByRecordNoStartingWith(prefix);
        // try until unique (handle race with DB constraint fallback)
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = String.format("VOL-%s-%04d", year, count + 1 + attempt);
            if (volumeRepo.findAll().stream().noneMatch(v -> candidate.equals(v.getRecordNo()))) {
                // still need DB unique check; just return candidate, DB will enforce
                return candidate;
            }
        }
        return String.format("VOL-%s-%04d", year, count + 1);
    }

    private boolean hasPermission(String permission) {
        return SecurityUtils.currentUser()
                .map(AuthenticatedUser::userId) // just to ensure authenticated
                .isPresent() && currentPermissions().contains(permission);
    }

    private java.util.Set<String> currentPermissions() {
        // resolve via SecurityContext authorities (HeaderAuthenticationFilter sets them)
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return java.util.Set.of();
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(java.util.stream.Collectors.toSet());
    }

    private VolumeResponse toResponse(VolumeRecord r) {
        return new VolumeResponse(
                r.getId(),
                r.getRecordNo(),
                r.getPeriod().getPeriodCode(),
                r.getContractId(),
                r.getCustomerName(),
                r.getServiceCode(),
                r.getServiceName(),
                r.getUnit(),
                r.getQuantity(),
                r.getNote(),
                r.getCreatedAt(),
                r.getCreatedBy(),
                r.getUpdatedAt()
        );
    }
}
