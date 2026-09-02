package com.abclogistics.pas.billing.service;

import com.abclogistics.pas.billing.domain.*;
import com.abclogistics.pas.billing.dto.*;
import com.abclogistics.pas.billing.grpc.*;
import com.abclogistics.pas.billing.repository.*;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
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
    private final EsignGrpcClient esignClient;

    public StatementService(PaymentStatementRepository statementRepo,
                            StatementLineRepository lineRepo,
                            StatementLineVolumeRepository lineVolumeRepo,
                            OutboxRepository outboxRepo,
                            ContractGrpcClient contractClient,
                            PricingGrpcClient pricingClient,
                            OperationsGrpcClient operationsClient,
                            WorkflowGrpcClient workflowClient,
                            EsignGrpcClient esignClient) {
        this.statementRepo = statementRepo;
        this.lineRepo = lineRepo;
        this.lineVolumeRepo = lineVolumeRepo;
        this.outboxRepo = outboxRepo;
        this.contractClient = contractClient;
        this.pricingClient = pricingClient;
        this.operationsClient = operationsClient;
        this.workflowClient = workflowClient;
        this.esignClient = esignClient;
    }

    @Transactional(readOnly = true)
    public Page<StatementResponse> list(int page, int size) {
        Page<PaymentStatement> statements = statementRepo.findAllSorted(PageRequest.of(page, size));
        return statements.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public StatementResponse getByStatementNo(String statementNo) {
        PaymentStatement stmt = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));
        return toResponse(stmt);
    }

    @Transactional
    public StatementResponse calculate(CalculateStatementRequest req) {
        UUID contractId = UUID.fromString(req.contractId());
        String periodCode = req.periodCode();

        // 1. Sync pull: contract
        GetContractResponse contract = contractClient.getContract(req.contractId());
        if (!"ACTIVE".equals(contract.getStatus()) && !"EXPIRED".equals(contract.getStatus())) {
            throw new FailedPreconditionException("Contract must be ACTIVE or EXPIRED, got: " + contract.getStatus());
        }

        // PAY-01: assert period within [valid_from, valid_to]
        LocalDate periodStart = LocalDate.parse(periodCode + "-01");
        LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);
        if (contract.getValidFrom() != null && !contract.getValidFrom().isEmpty()) {
            LocalDate validFrom = LocalDate.parse(contract.getValidFrom());
            if (periodEnd.isBefore(validFrom)) {
                throw new FailedPreconditionException("Period ends before contract valid_from (PAY-01)");
            }
        }
        if (contract.getValidTo() != null && !contract.getValidTo().isEmpty()) {
            LocalDate validTo = LocalDate.parse(contract.getValidTo());
            if (periodStart.isAfter(validTo)) {
                throw new FailedPreconditionException("Period starts after contract valid_to (PAY-01)");
            }
        }

        // 2. Sync pull: operations volumes (must be LOCKED)
        ListVolumesResponse volumes = operationsClient.listVolumes(req.contractId(), periodCode);
        if (!"LOCKED".equals(volumes.getPeriodState())) {
            throw new FailedPreconditionException("Period must be LOCKED, got: " + volumes.getPeriodState());
        }

        // 3. Sync pull: pricing effective version
        GetEffectivePriceListResponse priceList = pricingClient.getEffectivePriceList(
            req.contractId(), contract.getCustomerId(), contract.getServiceGroup(), volumes.getPeriodEnd());

        Map<String, PriceLine> priceLookup = priceList.getLinesList().stream()
            .collect(Collectors.toMap(PriceLine::getServiceCode, pl -> pl, (a, b) -> b));

        // 4. Validate all volumes are priced
        for (VolumeRecord vr : volumes.getVolumesList()) {
            if (!priceLookup.containsKey(vr.getServiceCode())) {
                throw new FailedPreconditionException("Unpriced service: " + vr.getServiceCode()
                    + " — no suitable price list for this service");
            }
        }

        // 4b. No duplicate live statement for (contract, period)
        boolean duplicateLive = statementRepo
            .existsByContractIdAndPeriodCodeAndAdjustsStatementIdIsNullAndStatusNotIn(
                contractId, periodCode,
                List.of(PaymentStatement.StatementStatus.CANCELLED, PaymentStatement.StatementStatus.REJECTED));
        if (duplicateLive) {
            throw new ConflictException("A live statement already exists for this contract+period");
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
        if (priceList.getVersionId() != null && !priceList.getVersionId().isBlank()) {
            statement.setPriceListVersionId(UUID.fromString(priceList.getVersionId()));
        }
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
        statement.setSubtotal(subtotal);
        statement.setTaxAmount(taxAmount);
        statement.setTotalAmount(subtotal.add(taxAmount));
        statementRepo.save(statement);

        // 8. Status history (D17)
        recordHistory(statement, null, PaymentStatement.StatementStatus.CALCULATED, StatusHistory.TriggerKind.U, null);

        // 9. Audit outbox
        auditOutbox(statement, "statement.calculated");

        return toResponse(statement);
    }

    @Transactional
    public StatementResponse reconcile(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        if (statement.getStatus() != PaymentStatement.StatementStatus.CALCULATED) {
            throw new FailedPreconditionException("Only CALCULATED statements can be reconciled");
        }

        // PAY-02: Re-fetch volumes and verify (period still LOCKED)
        ListVolumesResponse volumes = operationsClient.listVolumes(
            statement.getContractId().toString(), statement.getPeriodCode());
        if (!"LOCKED".equals(volumes.getPeriodState())) {
            throw new FailedPreconditionException("Period is no longer LOCKED");
        }

        PaymentStatement.StatementStatus oldStatus = statement.getStatus();
        statement.setStatus(PaymentStatement.StatementStatus.RECONCILED);
        statement.setReconciledAt(Instant.now());
        statement.setReconciledBy(SecurityUtils.currentUserId());
        statementRepo.save(statement);

        recordHistory(statement, oldStatus, PaymentStatement.StatementStatus.RECONCILED, StatusHistory.TriggerKind.U, null);
        auditOutbox(statement, "statement.reconciled");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse submit(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        if (statement.getStatus() != PaymentStatement.StatementStatus.RECONCILED) {
            throw new FailedPreconditionException("Only RECONCILED statements can be submitted");
        }

        // PAY-04 checks
        if (statement.getTotalAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new FailedPreconditionException("total_amount must be >= 0 (PAY-04)");
        }
        if (statement.getLines().isEmpty()) {
            throw new FailedPreconditionException("Statement must have at least 1 line (PAY-04)");
        }

        // ValidateStartable pre-check (registry §5, seq-06 m27)
        workflowClient.validateStartable("PAYMENT_STATEMENT");

        statement.setStatus(PaymentStatement.StatementStatus.SUBMITTED);
        statementRepo.save(statement);

        recordHistory(statement, PaymentStatement.StatementStatus.RECONCILED, PaymentStatement.StatementStatus.SUBMITTED, StatusHistory.TriggerKind.U, null);

        // Submit wiring: write workflow.start_requested to outbox (D4)
        UUID idempotencyKey = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.event(
            "workflow.start_requested",
            "PAYMENT_STATEMENT",
            statement.getId(),
            String.format("{\"idempotency_key\":\"%s\",\"document_type\":\"PAYMENT_STATEMENT\","
                + "\"document_id\":\"%s\",\"document_no\":\"%s\",\"customer_name\":\"%s\"}",
                idempotencyKey, statement.getId(), statement.getStatementNo(),
                statement.getCustomerName())
        );
        outboxRepo.save(event);

        auditOutbox(statement, "statement.submitted");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse updateStatus(String statementNo, String newStatus, String triggerRef) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        PaymentStatement.StatementStatus oldStatus = statement.getStatus();
        PaymentStatement.StatementStatus status = PaymentStatement.StatementStatus.valueOf(newStatus);

        // Validate allowed transitions per registry §9
        boolean valid = switch (status) {
            case DRAFT -> oldStatus == PaymentStatement.StatementStatus.CALCULATED
                || oldStatus == PaymentStatement.StatementStatus.REJECTED
                || oldStatus == PaymentStatement.StatementStatus.REVISION;
            case RECONCILED -> oldStatus == PaymentStatement.StatementStatus.CALCULATED;
            case SUBMITTED -> oldStatus == PaymentStatement.StatementStatus.RECONCILED;
            case APPROVED -> oldStatus == PaymentStatement.StatementStatus.SUBMITTED;
            case SIGNING -> oldStatus == PaymentStatement.StatementStatus.APPROVED;
            case SIGNED -> oldStatus == PaymentStatement.StatementStatus.SIGNING;
            case ISSUED -> oldStatus == PaymentStatement.StatementStatus.SIGNED;
            case CANCELLED -> oldStatus == PaymentStatement.StatementStatus.APPROVED
                || oldStatus == PaymentStatement.StatementStatus.SIGNED;
            case REJECTED, REVISION -> oldStatus == PaymentStatement.StatementStatus.SUBMITTED;
            default -> false;
        };

        if (!valid) {
            throw new ConflictException("Invalid transition: " + oldStatus + " → " + status);
        }

        statement.setStatus(status);

        if (status == PaymentStatement.StatementStatus.ISSUED) {
            statement.setIssuedAt(Instant.now());
            statement.setDueDate(computeDueDate(statement.getPaymentTerm(), statement.getPeriodEnd()));
        }

        statementRepo.save(statement);

        StatusHistory.TriggerKind kind = triggerRef != null && triggerRef.startsWith("W")
            ? StatusHistory.TriggerKind.W
            : triggerRef != null && triggerRef.startsWith("E")
                ? StatusHistory.TriggerKind.E : StatusHistory.TriggerKind.U;
        recordHistory(statement, oldStatus, status, kind, triggerRef);

        // On APPROVED→SIGNING: write esign.session_requested to outbox (D10, registry §6 third use)
        if (oldStatus == PaymentStatement.StatementStatus.APPROVED
            && status == PaymentStatement.StatementStatus.SIGNING) {
            UUID esignKey = UUID.randomUUID();
            OutboxEvent esignEvent = OutboxEvent.event(
                "esign.session_requested",
                "PAYMENT_STATEMENT",
                statement.getId(),
                String.format("{\"idempotency_key\":\"%s\",\"document_type\":\"PAYMENT_STATEMENT\","
                    + "\"document_id\":\"%s\",\"document_no\":\"%s\",\"signer_name\":\"%s\"}",
                    esignKey, statement.getId(), statement.getStatementNo(),
                    statement.getCustomerName() != null ? statement.getCustomerName() : "")
            );
            outboxRepo.save(esignEvent);
        }

        auditOutbox(statement, "statement.status_changed");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse editLine(String statementNo, EditLineRequest req) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        PaymentStatement.StatementStatus status = statement.getStatus();
        if (status != PaymentStatement.StatementStatus.DRAFT
            && status != PaymentStatement.StatementStatus.CALCULATED) {
            throw new FailedPreconditionException("Only DRAFT or CALCULATED statements can be edited");
        }

        Optional<StatementLine> lineOpt = statement.getLines().stream()
            .filter(l -> l.getLineNo() == req.lineNo())
            .findFirst();
        if (lineOpt.isEmpty()) {
            throw new NotFoundException("Line not found: " + req.lineNo());
        }

        StatementLine line = lineOpt.get();
        if (req.unitPrice() != null) line.setUnitPrice(req.unitPrice());
        if (req.quantity() != null) line.setQuantity(req.quantity());
        line.setSource(StatementLine.LineSource.MANUAL);
        if (req.note() != null) line.setNote(req.note());

        line.setAmount(line.getUnitPrice().multiply(line.getQuantity())
            .setScale(2, RoundingMode.HALF_UP));
        lineRepo.save(line);

        // If CALCULATED, flip back to DRAFT (D14f)
        if (status == PaymentStatement.StatementStatus.CALCULATED) {
            PaymentStatement.StatementStatus oldStatus = statement.getStatus();
            statement.setStatus(PaymentStatement.StatementStatus.DRAFT);
            recordHistory(statement, oldStatus, PaymentStatement.StatementStatus.DRAFT, StatusHistory.TriggerKind.U, null);
        }

        statementRepo.save(statement);
        auditOutbox(statement, "statement.line_edited");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse createAdjustment(String statementNo, AdjustmentRequest req) {
        PaymentStatement original = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        if (original.getStatus() != PaymentStatement.StatementStatus.ISSUED) {
            throw new FailedPreconditionException("Only ISSUED statements can have adjustments");
        }

        PaymentStatement adjustment = new PaymentStatement();
        adjustment.setStatementNo(generateStatementNo());
        adjustment.setContractId(original.getContractId());
        adjustment.setContractNo(original.getContractNo());
        adjustment.setCustomerId(original.getCustomerId());
        adjustment.setCustomerName(original.getCustomerName());
        adjustment.setPeriodCode(original.getPeriodCode());
        adjustment.setPeriodStart(original.getPeriodStart());
        adjustment.setPeriodEnd(original.getPeriodEnd());
        adjustment.setPriceListVersionId(original.getPriceListVersionId());
        adjustment.setPriceListNo(original.getPriceListNo());
        adjustment.setPriceListVersionNo(original.getPriceListVersionNo());
        adjustment.setPaymentTerm(original.getPaymentTerm());
        adjustment.setVatRate(original.getVatRate());
        adjustment.setCurrency(original.getCurrency());
        adjustment.setAdjustsStatementId(original.getId());
        adjustment.setStatus(PaymentStatement.StatementStatus.DRAFT);
        statementRepo.save(adjustment);

        BigDecimal subtotal = BigDecimal.ZERO;
        int lineNo = 1;
        for (AdjustmentRequest.AdjustmentLineInput input : req.lines()) {
            StatementLine line = new StatementLine();
            line.setStatement(adjustment);
            line.setLineNo(lineNo++);
            line.setServiceCode(input.serviceCode());
            line.setServiceName(input.serviceName());
            line.setUnit(input.unit());
            line.setUnitPrice(input.unitPrice());
            line.setQuantity(input.quantity());
            line.setAmount(input.unitPrice().multiply(input.quantity())
                .setScale(2, RoundingMode.HALF_UP));
            line.setSource(StatementLine.LineSource.MANUAL);
            line.setNote(input.note());
            lineRepo.save(line);
            subtotal = subtotal.add(line.getAmount());
        }

        BigDecimal taxAmount = subtotal.multiply(adjustment.getVatRate())
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        adjustment.setSubtotal(subtotal);
        adjustment.setTaxAmount(taxAmount);
        adjustment.setTotalAmount(subtotal.add(taxAmount));
        statementRepo.save(adjustment);

        recordHistory(adjustment, null, PaymentStatement.StatementStatus.DRAFT, StatusHistory.TriggerKind.U, null);
        auditOutbox(adjustment, "statement.adjustment_created");
        return toResponse(adjustment);
    }

    @Transactional
    public StatementResponse cancelStatement(String statementNo, String reason) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        PaymentStatement.StatementStatus oldStatus = statement.getStatus();
        if (oldStatus != PaymentStatement.StatementStatus.APPROVED
            && oldStatus != PaymentStatement.StatementStatus.SIGNED) {
            throw new FailedPreconditionException("Only APPROVED or SIGNED statements can be cancelled");
        }

        statement.setStatus(PaymentStatement.StatementStatus.CANCELLED);
        statementRepo.save(statement);

        recordHistory(statement, oldStatus, PaymentStatement.StatementStatus.CANCELLED, StatusHistory.TriggerKind.U, null);
        auditOutbox(statement, "statement.cancelled");
        return toResponse(statement);
    }

    @Transactional(readOnly = true)
    public WorkflowProgressResponse getWorkflowProgress(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));
        Object instance = workflowClient.getInstanceByDocument("PAYMENT_STATEMENT", statement.getId().toString());
        return new WorkflowProgressResponse(statementNo, instance);
    }

    private void recordHistory(PaymentStatement statement,
                               PaymentStatement.StatementStatus fromStatus,
                               PaymentStatement.StatementStatus toStatus,
                               StatusHistory.TriggerKind kind,
                               String triggerRef) {
        StatusHistory history = new StatusHistory();
        history.setStatement(statement);
        history.setFromStatus(fromStatus != null ? fromStatus.name() : null);
        history.setToStatus(toStatus.name());
        history.setTriggerKind(kind);
        history.setTriggerRef(triggerRef);
        history.setActorId(SecurityUtils.currentUserId());
        history.setActorName(SecurityUtils.currentUserName());
        history.setOccurredAt(Instant.now());
        statement.getStatusHistory().add(history);
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
        long seq = statementRepo.nextStatementNoSeq();
        int year = java.time.Year.now().getValue();
        return String.format("PMT-%d-%04d", year, seq);
    }

    private void auditOutbox(PaymentStatement statement, String action) {
        UUID actorId = SecurityUtils.currentUserId();
        String actorName = SecurityUtils.currentUserName();
        String payload = String.format(
            "{\"source_service\":\"billing\",\"entity_type\":\"PAYMENT_STATEMENT\","
            + "\"entity_id\":\"%s\",\"entity_no\":\"%s\",\"action\":\"%s\","
            + "\"actor_id\":\"%s\",\"actor_name\":\"%s\",\"actor_department\":\"\","
            + "\"before_status\":null,\"after_status\":\"%s\",\"changes\":{},\"note\":null,"
            + "\"ip_address\":null,\"occurred_at\":\"%s\"}",
            statement.getId(), statement.getStatementNo(), action,
            actorId != null ? actorId.toString() : "null",
            actorName != null ? actorName : "",
            statement.getStatus(),
            Instant.now());
        OutboxEvent event = OutboxEvent.audit("PAYMENT_STATEMENT", statement.getId(), payload);
        outboxRepo.save(event);
    }


    private StatementResponse toResponse(PaymentStatement ps) {
        List<StatementLineResponse> lineResponses = ps.getLines().stream()
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
        List<VolumeLinkResponse> links = line.getVolumeLinks() != null
            ? line.getVolumeLinks().stream()
                .map(vl -> new VolumeLinkResponse(vl.getId(), vl.getVolumeRecordId(), vl.getRecordNo(), vl.getQuantity()))
                .toList()
            : List.of();
        return new StatementLineResponse(
            line.getId(), line.getLineNo(), line.getServiceCode(), line.getServiceName(),
            line.getUnit(), line.getUnitPrice(), line.getQuantity(), line.getAmount(),
            line.getSource().name(), line.getNote(), links
        );
    }
}
