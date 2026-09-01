package com.abclogistics.pas.billing.service;

import com.abclogistics.pas.billing.domain.*;
import com.abclogistics.pas.billing.dto.*;
import com.abclogistics.pas.billing.grpc.*;
import com.abclogistics.pas.billing.repository.*;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.operations.grpc.VolumeRecord;
import com.abclogistics.pas.pricing.grpc.GetEffectivePriceListResponse;
import com.abclogistics.pas.pricing.grpc.PriceLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private final PaymentStatementRepository statementRepo;
    private final StatementLineRepository lineRepo;
    private final StatementLineVolumeRepository lineVolumeRepo;
    private final OutboxRepository outboxRepo;
    private final ContractGrpcClient contractClient;
    private final PricingGrpcClient pricingClient;
    private final OperationsGrpcClient operationsClient;
    private final WorkflowGrpcClient workflowClient;

    public StatementService(PaymentStatementRepository statementRepo,
                            StatementLineRepository lineRepo,
                            StatementLineVolumeRepository lineVolumeRepo,
                            OutboxRepository outboxRepo,
                            ContractGrpcClient contractClient,
                            PricingGrpcClient pricingClient,
                            OperationsGrpcClient operationsClient,
                            WorkflowGrpcClient workflowClient) {
        this.statementRepo = statementRepo;
        this.lineRepo = lineRepo;
        this.lineVolumeRepo = lineVolumeRepo;
        this.outboxRepo = outboxRepo;
        this.contractClient = contractClient;
        this.pricingClient = pricingClient;
        this.operationsClient = operationsClient;
        this.workflowClient = workflowClient;
    }

    @Transactional(readOnly = true)
    public PageResponse<StatementResponse> list(int page, int size) {
        Page<PaymentStatement> statements = statementRepo.findAllSorted(PageRequest.of(page, size));
        List<StatementResponse> data = statements.getContent().stream()
            .map(this::toResponse)
            .toList();
        return new PageResponse<>(data, new Meta(page, size));
    }

    @Transactional(readOnly = true)
    public StatementResponse getByStatementNo(String statementNo) {
        PaymentStatement stmt = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new IllegalArgumentException("Statement not found: " + statementNo));
        return toResponse(stmt);
    }

    @Transactional
    public StatementResponse calculate(CalculateStatementRequest req) {
        UUID contractId = UUID.fromString(req.contractId());
        String periodCode = req.periodCode();

        // 1. Sync pull: contract
        GetContractResponse contract = contractClient.getContract(req.contractId());
        if (!"ACTIVE".equals(contract.getStatus()) && !"EXPIRED".equals(contract.getStatus())) {
            throw new IllegalStateException("Contract must be ACTIVE or EXPIRED, got: " + contract.getStatus());
        }

        // 2. Sync pull: operations volumes (must be LOCKED)
        ListVolumesResponse volumes = operationsClient.listVolumes(req.contractId(), periodCode);
        if (!"LOCKED".equals(volumes.getPeriodState())) {
            throw new IllegalStateException("Period must be LOCKED, got: " + volumes.getPeriodState());
        }

        // 3. Sync pull: pricing effective version
        GetEffectivePriceListResponse priceList = pricingClient.getEffectivePriceList(
            req.contractId(), contract.getCustomerId(), contract.getServiceGroup(), volumes.getPeriodEnd());

        // Build price lookup: service_code -> PriceLine
        Map<String, PriceLine> priceLookup = priceList.getLinesList().stream()
            .collect(Collectors.toMap(PriceLine::getServiceCode, pl -> pl, (a, b) -> b));

        // 4. Validate all volumes are priced
        for (VolumeRecord vr : volumes.getVolumesList()) {
            if (!priceLookup.containsKey(vr.getServiceCode())) {
                throw new IllegalStateException("Unpriced service: " + vr.getServiceCode()
                    + " — no suitable price list for this service");
            }
        }

        // 5. Create statement
        PaymentStatement statement = new PaymentStatement();
        statement.setStatementNo(generateStatementNo());
        statement.setContractId(contractId);
        statement.setContractNo(contract.getContractNo());
        statement.setCustomerId(UUID.fromString(contract.getCustomerId()));
        statement.setCustomerName(contract.getCustomerName());
        statement.setPeriodCode(periodCode);
        statement.setPeriodStart(LocalDate.parse(volumes.getPeriodStart()));
        statement.setPeriodEnd(LocalDate.parse(volumes.getPeriodEnd()));
        statement.setPriceListVersionId(null);
        statement.setPriceListNo(priceList.getPriceListNo());
        statement.setPriceListVersionNo(priceList.getVersionNo());
        statement.setPaymentTerm(contract.getPaymentTerm());
        statement.setVatRate(BigDecimal.valueOf(contract.getVatRate()));
        statement.setCurrency(contract.getCurrency());
        statement.setStatus(PaymentStatement.StatementStatus.CALCULATED);
        statementRepo.save(statement);

        // 6. Create lines (one per service code)
        Map<String, List<VolumeRecord>> volumesByService = volumes.getVolumesList().stream()
            .collect(Collectors.groupingBy(VolumeRecord::getServiceCode));

        int lineNo = 1;
        BigDecimal subtotal = BigDecimal.ZERO;

        for (Map.Entry<String, List<VolumeRecord>> entry : volumesByService.entrySet()) {
            String serviceCode = entry.getKey();
            List<VolumeRecord> serviceVolumes = entry.getValue();
            PriceLine priceLine = priceLookup.get(serviceCode);

            BigDecimal totalQty = serviceVolumes.stream()
                .map(vr -> BigDecimal.valueOf(vr.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal amount = totalQty.multiply(BigDecimal.valueOf(priceLine.getUnitPrice()))
                .setScale(2, RoundingMode.HALF_UP);

            StatementLine line = new StatementLine();
            line.setStatement(statement);
            line.setLineNo(lineNo++);
            line.setServiceCode(serviceCode);
            line.setServiceName(priceLine.getServiceName());
            line.setUnit(priceLine.getUnit());
            line.setUnitPrice(BigDecimal.valueOf(priceLine.getUnitPrice()));
            line.setQuantity(totalQty);
            line.setAmount(amount);
            line.setSource(StatementLine.LineSource.CALCULATED);
            lineRepo.save(line);

            // Link volume records — store record_no as the reference
            for (VolumeRecord vr : serviceVolumes) {
                StatementLineVolume link = new StatementLineVolume();
                link.setLine(line);
                link.setVolumeRecordId(vr.getRecordNo());
                link.setRecordNo(vr.getRecordNo());
                link.setQuantity(BigDecimal.valueOf(vr.getQuantity()));
                lineVolumeRepo.save(link);
            }

            subtotal = subtotal.add(amount);
        }

        // 7. Compute tax and total
        BigDecimal taxAmount = subtotal.multiply(statement.getVatRate())
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = subtotal.add(taxAmount);

        statement.setSubtotal(subtotal);
        statement.setTaxAmount(taxAmount);
        statement.setTotalAmount(totalAmount);
        statementRepo.save(statement);

        // 8. Audit outbox
        auditOutbox(statement, "statement.calculated");

        return toResponse(statement);
    }

    @Transactional
    public StatementResponse reconcile(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new IllegalArgumentException("Statement not found: " + statementNo));

        if (statement.getStatus() != PaymentStatement.StatementStatus.CALCULATED) {
            throw new IllegalStateException("Only CALCULATED statements can be reconciled");
        }

        // Re-fetch volumes and verify
        ListVolumesResponse volumes = operationsClient.listVolumes(
            statement.getContractId().toString(), statement.getPeriodCode());
        if (!"LOCKED".equals(volumes.getPeriodState())) {
            throw new IllegalStateException("Period is no longer LOCKED");
        }

        statement.setStatus(PaymentStatement.StatementStatus.RECONCILED);
        statement.setReconciledAt(Instant.now());
        statement.setReconciledBy(SecurityUtils.currentUserId());
        statementRepo.save(statement);

        auditOutbox(statement, "statement.reconciled");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse submit(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new IllegalArgumentException("Statement not found: " + statementNo));

        if (statement.getStatus() != PaymentStatement.StatementStatus.RECONCILED) {
            throw new IllegalStateException("Only RECONCILED statements can be submitted");
        }

        // PAY-04 checks
        if (statement.getTotalAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("total_amount must be >= 0 (PAY-04)");
        }

        statement.setStatus(PaymentStatement.StatementStatus.SUBMITTED);
        statementRepo.save(statement);

        // Submit wiring: write workflow.start_requested to outbox (D4)
        OutboxEvent event = OutboxEvent.event(
            "workflow.start_requested",
            "PAYMENT_STATEMENT",
            UUID.randomUUID(),
            String.format("{\"idempotency_key\":\"%s\",\"document_type\":\"PAYMENT_STATEMENT\","
                + "\"document_id\":\"%s\",\"document_no\":\"%s\",\"customer_name\":\"%s\"}",
                UUID.randomUUID(), statement.getId(), statement.getStatementNo(),
                statement.getCustomerName())
        );
        outboxRepo.save(event);

        auditOutbox(statement, "statement.submitted");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse updateStatus(String statementNo, String newStatus, String triggerRef) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new IllegalArgumentException("Statement not found: " + statementNo));

        PaymentStatement.StatementStatus oldStatus = statement.getStatus();
        PaymentStatement.StatementStatus status = PaymentStatement.StatementStatus.valueOf(newStatus);

        // Handle REJECTED/REVISION → DRAFT (revise)
        if ((status == PaymentStatement.StatementStatus.REJECTED
                || status == PaymentStatement.StatementStatus.REVISION)
            && oldStatus == PaymentStatement.StatementStatus.SUBMITTED) {
            statement.setStatus(PaymentStatement.StatementStatus.DRAFT);
        } else if (status == PaymentStatement.StatementStatus.APPROVED
            && oldStatus == PaymentStatement.StatementStatus.SUBMITTED) {
            statement.setStatus(PaymentStatement.StatementStatus.APPROVED);
        } else if (status == PaymentStatement.StatementStatus.SIGNING
            && oldStatus == PaymentStatement.StatementStatus.APPROVED) {
            statement.setStatus(PaymentStatement.StatementStatus.SIGNING);
        } else if (status == PaymentStatement.StatementStatus.SIGNED
            && oldStatus == PaymentStatement.StatementStatus.SIGNING) {
            statement.setStatus(PaymentStatement.StatementStatus.SIGNED);
        } else if (status == PaymentStatement.StatementStatus.ISSUED
            && oldStatus == PaymentStatement.StatementStatus.SIGNED) {
            statement.setStatus(PaymentStatement.StatementStatus.ISSUED);
            statement.setIssuedAt(Instant.now());
            // Compute due_date from payment_term
            statement.setDueDate(computeDueDate(statement.getPaymentTerm(), statement.getPeriodEnd()));
        } else if (status == PaymentStatement.StatementStatus.CANCELLED
            && (oldStatus == PaymentStatement.StatementStatus.APPROVED
                || oldStatus == PaymentStatement.StatementStatus.SIGNED)) {
            statement.setStatus(PaymentStatement.StatementStatus.CANCELLED);
        } else {
            throw new IllegalStateException("Invalid transition: " + oldStatus + " → " + status);
        }

        statementRepo.save(statement);

        // Status history
        StatusHistory history = new StatusHistory();
        history.setStatement(statement);
        history.setFromStatus(oldStatus.name());
        history.setToStatus(status.name());
        history.setTriggerKind(triggerRef != null && triggerRef.startsWith("W")
            ? StatusHistory.TriggerKind.W
            : triggerRef != null && triggerRef.startsWith("E")
                ? StatusHistory.TriggerKind.E : StatusHistory.TriggerKind.U);
        history.setTriggerRef(triggerRef);
        history.setActorId(SecurityUtils.currentUserId());
        history.setOccurredAt(Instant.now());
        statement.getStatusHistory().add(history);

        auditOutbox(statement, "statement.status_changed");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse getSigningPayload(String statementId) {
        PaymentStatement statement = statementRepo.findById(Long.parseLong(statementId))
            .orElseThrow(() -> new IllegalArgumentException("Statement not found: " + statementId));

        PaymentStatement.StatementStatus status = statement.getStatus();
        if (status != PaymentStatement.StatementStatus.APPROVED
            && status != PaymentStatement.StatementStatus.SIGNING) {
            throw new IllegalStateException("Statement must be APPROVED or SIGNING for signing payload");
        }

        return toResponse(statement);
    }

    private LocalDate computeDueDate(String paymentTerm, LocalDate baseDate) {
        if (paymentTerm == null || paymentTerm.isBlank()) return baseDate.plusDays(30);
        String upper = paymentTerm.toUpperCase(Locale.ROOT);
        if (upper.contains("NET 30") || upper.contains("NET30")) return baseDate.plusDays(30);
        if (upper.contains("NET 60") || upper.contains("NET60")) return baseDate.plusDays(60);
        if (upper.contains("NET 90") || upper.contains("NET90")) return baseDate.plusDays(90);
        return baseDate.plusDays(30);
    }

    private String generateStatementNo() {
        long seq = statementRepo.count() + 1;
        return "PMT-" + String.format("%04d", seq);
    }

    private void auditOutbox(PaymentStatement statement, String eventType) {
        OutboxEvent event = OutboxEvent.event(
            eventType,
            "PAYMENT_STATEMENT",
            UUID.randomUUID(),
            String.format("{\"statementNo\":\"%s\",\"status\":\"%s\"}",
                statement.getStatementNo(), statement.getStatus())
        );
        outboxRepo.save(event);
    }

    private StatementResponse toResponse(PaymentStatement ps) {
        List<StatementLineResponse> lineResponses = lineRepo.findByStatementIdOrderByLineNo(ps.getId())
            .stream()
            .map(this::toLineResponse)
            .toList();
        return new StatementResponse(
            ps.getId(), ps.getStatementNo(),
            ps.getContractId() != null ? ps.getContractId().toString() : null,
            ps.getContractNo(),
            ps.getCustomerId() != null ? ps.getCustomerId().toString() : null,
            ps.getCustomerName(),
            ps.getPeriodCode(),
            ps.getPeriodStart() != null ? ps.getPeriodStart().toString() : null,
            ps.getPeriodEnd() != null ? ps.getPeriodEnd().toString() : null,
            ps.getPriceListNo(), ps.getPriceListVersionNo(),
            ps.getPaymentTerm(), ps.getVatRate(),
            ps.getSubtotal(), ps.getTaxAmount(), ps.getTotalAmount(),
            ps.getCurrency(), ps.getStatus().name(),
            ps.getAdjustsStatementId(),
            ps.getReconciledAt() != null ? ps.getReconciledAt().toString() : null,
            ps.getIssuedAt() != null ? ps.getIssuedAt().toString() : null,
            ps.getDueDate(), ps.getVersion(),
            lineResponses
        );
    }

    private StatementLineResponse toLineResponse(StatementLine line) {
        List<VolumeLinkResponse> links = lineVolumeRepo.findByLineId(line.getId())
            .stream()
            .map(vl -> new VolumeLinkResponse(vl.getId(), vl.getVolumeRecordId(), vl.getRecordNo(), vl.getQuantity()))
            .toList();
        return new StatementLineResponse(
            line.getId(), line.getLineNo(), line.getServiceCode(), line.getServiceName(),
            line.getUnit(), line.getUnitPrice(), line.getQuantity(), line.getAmount(),
            line.getSource().name(), line.getNote(), links
        );
    }
}
