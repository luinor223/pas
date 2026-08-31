package com.abclogistics.pas.operations.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.operations.domain.OperationPeriod;
import com.abclogistics.pas.operations.domain.VolumeRecord;
import com.abclogistics.pas.operations.dto.VolumeResponse;
import com.abclogistics.pas.operations.error.FailedPreconditionException;
import com.abclogistics.pas.operations.grpc.ContractGrpcClient;
import com.abclogistics.pas.operations.grpc.PricingGrpcClient;
import com.abclogistics.pas.operations.repository.OperationPeriodRepository;
import com.abclogistics.pas.operations.repository.VolumeRecordRepository;
import com.abclogistics.pas.pricing.grpc.GetServiceItemResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VolumeService {

    private static final Logger log = LoggerFactory.getLogger(VolumeService.class);

    private final OperationPeriodRepository periodRepo;
    private final VolumeRecordRepository volumeRepo;
    private final ContractGrpcClient contractClient;
    private final PricingGrpcClient pricingClient;
    private final AuditRecorder audit;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    public VolumeService(OperationPeriodRepository periodRepo,
                         VolumeRecordRepository volumeRepo,
                         ContractGrpcClient contractClient,
                         PricingGrpcClient pricingClient,
                         AuditRecorder audit,
                         JdbcTemplate jdbc,
                         PlatformTransactionManager txManager) {
        this.periodRepo = periodRepo;
        this.volumeRepo = volumeRepo;
        this.contractClient = contractClient;
        this.pricingClient = pricingClient;
        this.audit = audit;
        this.jdbc = jdbc;
        this.txManager = txManager;
    }

    // Outer validation + 2-try self-healing for manual INSERT / dump / sequence drift (P1 residual)
    // Sequence is race-free for sole writer, but restored dump may have record_no that collides with nextval
    // → catch 409 and regenerate once. Cost <5 loc, keeps save() path self-healing instead of bubbling 409.
    public VolumeResponse create(UUID contractId, String periodCode, String serviceCode, BigDecimal quantity, String note) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
        validatePeriodCode(periodCode);
        // permission + contract/pricing validation outside TX (no DB write yet)
        OperationPeriod periodCheck = periodRepo.findByPeriodCode(periodCode)
                .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));
        if (periodCheck.isLocked() && !hasPermission("volume:edit_locked")) {
            throw new AccessDeniedException("Period is locked; volume:edit_locked required");
        }
        GetServiceItemResponse serviceItem = pricingClient.getServiceItem(serviceCode);
        if (!serviceItem.getIsActive()) {
            throw new FailedPreconditionException("Service item not active: " + serviceCode);
        }
        String serviceName = serviceItem.getName();
        String unit = serviceItem.getUnit();
        GetContractResponse contract = contractClient.getContract(contractId);
        String customerName = contract.getCustomerName();
        UUID actor = SecurityUtils.currentUserId();

        // 2-try loop with REQUIRES_NEW so duplicate 409 does not mark outer TX rollbackOnly
        UUID tmpCustomerId;
        try {
            tmpCustomerId = UUID.fromString(contract.getCustomerId());
        } catch (Exception e) {
            tmpCustomerId = null;
        }
        final UUID customerId = tmpCustomerId;
        final String serviceNameFinal = serviceName;
        final String unitFinal = unit;
        final String customerNameFinal = customerName;
        final BigDecimal quantityFinal = quantity;
        final String noteFinal = note;
        final UUID actorFinal = actor;
        // generateRecordNo outside new TX is intentional (nextval not rolled back), but period lock re-check must be inside
        for (int attempt = 0; attempt < 2; attempt++) {
            String recordNo = generateRecordNo(periodCode);
            final String currentRecNo = recordNo;
            try {
                TransactionTemplate tt = new TransactionTemplate(txManager);
                tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return tt.execute(status -> {
                    OperationPeriod period = periodRepo.findByPeriodCode(periodCode)
                            .orElseThrow(() -> new NotFoundException("Period not found: " + periodCode));
                    // P0-1 fix: re-validate isLocked inside new TX after period is re-read (TOCTOU)
                    if (period.isLocked() && !hasPermission("volume:edit_locked")) {
                        throw new AccessDeniedException("Period is locked; volume:edit_locked required");
                    }
                    VolumeRecord record = VolumeRecord.create(
                            period, currentRecNo, contractId, customerId, customerNameFinal,
                            serviceCode, serviceNameFinal, unitFinal, quantityFinal, noteFinal, actorFinal);
                    volumeRepo.save(record);
                    Map<String, Object> changes = Map.of(
                            "contractId", contractId.toString(),
                            "periodCode", periodCode,
                            "serviceCode", serviceCode,
                            "quantity", quantityFinal.toPlainString()
                    );
                    audit.record("VOLUME_RECORD", record.getId(), currentRecNo, "volume.created",
                            null, null, null, changes);
                    return toResponse(record);
                });
            } catch (DataIntegrityViolationException e) {
                String msg = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : "";
                boolean isDup = msg != null && msg.toLowerCase().contains("record_no");
                if (!isDup || attempt == 1) throw e;
                log.warn("Duplicate record_no {} on attempt {}/2 (dump/sequence drift), retrying with nextval", recordNo, attempt + 1);
            }
        }
        throw new IllegalStateException("Failed to create volume after retry");
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
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
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
        // O(1) global sequence — avoids O(N) count + full scan and race. Known deviation: global monotonic
        // (VOL-2027-0002 after VOL-2026-0010) not per-year reset; uniqueness per year still via prefix.
        // Strict per-year would need volume_record_counter + SELECT FOR UPDATE — keep global for simplicity.
        try {
            Long seq = jdbc.queryForObject("SELECT nextval('operations.volume_record_no_seq')", Long.class);
            if (seq != null) {
                return String.format("VOL-%s-%04d", year, seq);
            }
        } catch (Exception e) {
            log.warn("Sequence nextval failed, falling back to count: {}", e.getMessage());
        }
        // Fallback (should not happen in prod): count-based
        long count = volumeRepo.countByRecordNoStartingWith("VOL-" + year + "-");
        return String.format("VOL-%s-%04d", year, count + 1);
    }

    private void validatePeriodCode(String code) {
        try {
            java.time.YearMonth.parse(code);
            if (!code.matches("^\\d{4}-(0[1-9]|1[0-2])$")) throw new java.time.format.DateTimeParseException("Invalid", code, 0);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid period_code, expected YYYY-MM: " + code);
        }
    }

    private boolean hasPermission(String permission) {
        return SecurityUtils.currentUser().isPresent() && currentPermissions().contains(permission);
    }

    private java.util.Set<String> currentPermissions() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
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
