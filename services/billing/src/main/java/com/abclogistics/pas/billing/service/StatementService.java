package com.abclogistics.pas.billing.service;

import com.abclogistics.pas.billing.domain.*;
import com.abclogistics.pas.billing.dto.*;
import com.abclogistics.pas.billing.error.UnprocessableEntityException;
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
    private final com.abclogistics.pas.common.audit.AuditRecorder auditRecorder;

    public StatementService(PaymentStatementRepository statementRepo,
                            StatementLineRepository lineRepo,
                            StatementLineVolumeRepository lineVolumeRepo,
                            OutboxRepository outboxRepo,
                            ContractGrpcClient contractClient,
                            PricingGrpcClient pricingClient,
                            OperationsGrpcClient operationsClient,
                            WorkflowGrpcClient workflowClient,
                            EsignGrpcClient esignClient,
                            com.abclogistics.pas.common.audit.AuditRecorder auditRecorder) {
        this.statementRepo = statementRepo;
        this.lineRepo = lineRepo;
        this.lineVolumeRepo = lineVolumeRepo;
        this.outboxRepo = outboxRepo;
        this.contractClient = contractClient;
        this.pricingClient = pricingClient;
        this.operationsClient = operationsClient;
        this.workflowClient = workflowClient;
        this.esignClient = esignClient;
        this.auditRecorder = auditRecorder;
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

        // 4. Validate all volumes are priced (seq-06: else 422 PAY-01)
        for (VolumeRecord vr : volumes.getVolumesList()) {
            if (!priceLookup.containsKey(vr.getServiceCode())) {
                throw new UnprocessableEntityException("Unpriced service: " + vr.getServiceCode()
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
        statementRepo.saveAndFlush(statement);

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
            statement.getLines().add(line);

            for (VolumeRecord vr : serviceVolumes) {
                StatementLineVolume link = new StatementLineVolume();
                link.setLine(line);
                link.setVolumeRecordId(vr.getRecordNo());
                link.setRecordNo(vr.getRecordNo());
                link.setQuantity(BigDecimal.valueOf(vr.getQuantity()));
                lineVolumeRepo.save(link);
                line.getVolumeLinks().add(link);
            }

            subtotal = subtotal.add(amount);
        }

        // 7. Compute tax and total
        BigDecimal taxAmount = subtotal.multiply(statement.getVatRate())
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        statement.setSubtotal(subtotal);
        statement.setTaxAmount(taxAmount);
        statement.setTotalAmount(subtotal.add(taxAmount));
        statementRepo.saveAndFlush(statement);

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

        if (statement.getAdjustsStatementId() == null) {
            // seq-06 m24 reconciliation re-checks: period still LOCKED, contract still in force
            // during the period, the resolved price version still matches the snapshot, and no
            // post-lock volume edit (volume:edit_locked) drifted the billed quantities.
            ListVolumesResponse volumes = operationsClient.listVolumes(
                statement.getContractId().toString(), statement.getPeriodCode());
            if (!"LOCKED".equals(volumes.getPeriodState())) {
                throw new FailedPreconditionException("Period is no longer LOCKED");
            }
            GetContractResponse liveContract =
                contractClient.getContract(statement.getContractId().toString());
            assertContractInForce(liveContract,
                statement.getPeriodStart(), statement.getPeriodEnd());
            GetEffectivePriceListResponse current = pricingClient.getEffectivePriceList(
                statement.getContractId().toString(), liveContract.getCustomerId(),
                liveContract.getServiceGroup(), volumes.getPeriodEnd());
            if (!java.util.Objects.equals(current.getPriceListNo(), statement.getPriceListNo())
                || current.getVersionNo() != java.util.Objects.requireNonNullElse(
                    statement.getPriceListVersionNo(), -1)) {
                throw new UnprocessableEntityException(
                    "Price version drifted since calculation (snapshot "
                        + statement.getPriceListNo() + " v" + statement.getPriceListVersionNo()
                        + ", now " + current.getPriceListNo() + " v" + current.getVersionNo()
                        + "); recalculate before reconciling");
            }
            Map<String, BigDecimal> currentQty = volumes.getVolumesList().stream()
                .collect(Collectors.toMap(VolumeRecord::getRecordNo,
                    vr -> BigDecimal.valueOf(vr.getQuantity()), (a, b) -> b));
            List<String> drifted = statement.getLines().stream()
                .flatMap(l -> l.getVolumeLinks().stream())
                .filter(link -> !java.util.Objects.equals(
                    link.getQuantity().stripTrailingZeros(),
                    java.util.Objects.requireNonNullElse(
                        currentQty.get(link.getRecordNo()), BigDecimal.valueOf(-1)).stripTrailingZeros()))
                .map(StatementLineVolume::getRecordNo)
                .toList();
            if (!drifted.isEmpty()) {
                throw new UnprocessableEntityException(
                    "Volume quantities changed since calculation for records " + drifted
                        + " (post-lock edit); recalculate before reconciling");
            }
        }

        PaymentStatement.StatementStatus oldStatus = statement.getStatus();
        statement.setStatus(PaymentStatement.StatementStatus.RECONCILED);
        statement.setReconciledAt(Instant.now());
        statement.setReconciledBy(SecurityUtils.currentUserId());
        statementRepo.saveAndFlush(statement);

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

        if (statement.getAdjustsStatementId() == null) {
            // seq-06 m26: every LOCKED volume of (contract, period) must be mapped.
            // Adjustments are MANUAL-only deltas (PAY-05/nt6), not re-statements, so exempt.
            java.util.Set<String> linked = statement.getLines().stream()
                .flatMap(l -> l.getVolumeLinks().stream())
                .map(StatementLineVolume::getRecordNo)
                .collect(Collectors.toSet());
            List<String> unmapped = operationsClient.listVolumes(
                    statement.getContractId().toString(), statement.getPeriodCode())
                .getVolumesList().stream()
                .map(VolumeRecord::getRecordNo)
                .filter(recordNo -> !linked.contains(recordNo))
                .toList();
            if (!unmapped.isEmpty()) {
                throw new UnprocessableEntityException(
                    "Unmapped locked volumes for this contract+period: " + unmapped + " (PAY-04/m26)");
            }
        }

        // ValidateStartable pre-check (registry §5, seq-06 m27)
        workflowClient.validateStartable("PAYMENT_STATEMENT");
        statement.setStatus(PaymentStatement.StatementStatus.SUBMITTED);
        statementRepo.saveAndFlush(statement);

        recordHistory(statement, PaymentStatement.StatementStatus.RECONCILED, PaymentStatement.StatementStatus.SUBMITTED, StatusHistory.TriggerKind.U, null);

        // Submit wiring: write workflow.start_requested to outbox (D4)
        UUID idempotencyKey = UUID.randomUUID();
        UUID requestedById = SecurityUtils.currentUserId();
        String requestedByName = SecurityUtils.currentUserName();
        OutboxEvent event = OutboxEvent.event(
            "workflow.start_requested",
            "PAYMENT_STATEMENT",
            statement.getId(),
            String.format("{\"idempotency_key\":\"%s\",\"document_type\":\"PAYMENT_STATEMENT\","
                + "\"document_id\":\"%s\",\"document_no\":\"%s\",\"customer_name\":\"%s\","
                + "\"requested_by_id\":\"%s\",\"requested_by_name\":\"%s\"}",
                idempotencyKey, statement.getId(), statement.getStatementNo(),
                escape(statement.getCustomerName()),
                requestedById != null ? requestedById : "",
                escape(requestedByName))
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

        statementRepo.saveAndFlush(statement);

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
        // seq-06 m18 version guard: stale writers lose loudly instead of overwriting
        if (req.version() == null || req.version() != statement.getVersion()) {
            throw new UnprocessableEntityException(
                "Stale statement version (got " + req.version() + ", current " + statement.getVersion()
                    + "); reload and retry");
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

        // Totals must follow the line — otherwise submit's PAY-04 check is vacuous.
        recomputeTotals(statement);

        // If CALCULATED, flip back to DRAFT (D14f)
        if (status == PaymentStatement.StatementStatus.CALCULATED) {
            PaymentStatement.StatementStatus oldStatus = statement.getStatus();
            statement.setStatus(PaymentStatement.StatementStatus.DRAFT);
            recordHistory(statement, oldStatus, PaymentStatement.StatementStatus.DRAFT, StatusHistory.TriggerKind.U, null);
        }

        statementRepo.saveAndFlush(statement);
        auditOutbox(statement, "statement.line_edited");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse addLine(String statementNo, AddLineRequest req) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        PaymentStatement.StatementStatus status = statement.getStatus();
        if (status != PaymentStatement.StatementStatus.DRAFT
            && status != PaymentStatement.StatementStatus.CALCULATED) {
            throw new FailedPreconditionException("Only DRAFT or CALCULATED statements can be edited");
        }
        if (req.version() == null || req.version() != statement.getVersion()) {
            throw new UnprocessableEntityException(
                "Stale statement version (got " + req.version() + ", current " + statement.getVersion()
                    + "); reload and retry");
        }

        int nextLineNo = statement.getLines().stream()
            .mapToInt(StatementLine::getLineNo).max().orElse(0) + 1;
        StatementLine line = new StatementLine();
        line.setStatement(statement);
        line.setLineNo(nextLineNo);
        line.setServiceCode(req.serviceCode());
        line.setServiceName(req.serviceName());
        line.setUnit(req.unit());
        line.setUnitPrice(req.unitPrice());
        line.setQuantity(req.quantity());
        line.setAmount(req.unitPrice().multiply(req.quantity())
            .setScale(2, RoundingMode.HALF_UP));
        line.setSource(StatementLine.LineSource.MANUAL);
        line.setNote(req.note());
        lineRepo.save(line);
        statement.getLines().add(line);

        recomputeTotals(statement);

        if (status == PaymentStatement.StatementStatus.CALCULATED) {
            statement.setStatus(PaymentStatement.StatementStatus.DRAFT);
            recordHistory(statement, status, PaymentStatement.StatementStatus.DRAFT, StatusHistory.TriggerKind.U, null);
        }

        statementRepo.saveAndFlush(statement);
        auditOutbox(statement, "statement.line_added");
        return toResponse(statement);
    }

    /**
     * Refreshes only {@code CALCULATED} lines from fresh upstream pulls; {@code MANUAL} lines are
     * preserved untouched (seq-06 m20/nt4) and renumbered CALCULATED lines continue after them.
     * Totals cover both sources.
     */
    private void recalculateCalculatedLines(PaymentStatement statement) {
        GetContractResponse contract = contractClient.getContract(statement.getContractId().toString());
        ListVolumesResponse volumes = operationsClient.listVolumes(
            statement.getContractId().toString(), statement.getPeriodCode());
        if (!"LOCKED".equals(volumes.getPeriodState())) {
            throw new FailedPreconditionException("Period must be LOCKED, got: " + volumes.getPeriodState());
        }
        GetEffectivePriceListResponse priceList = pricingClient.getEffectivePriceList(
            statement.getContractId().toString(), contract.getCustomerId(),
            contract.getServiceGroup(), volumes.getPeriodEnd());
        Map<String, PriceLine> priceLookup = priceList.getLinesList().stream()
            .collect(Collectors.toMap(PriceLine::getServiceCode, pl -> pl, (a, b) -> b));
        for (VolumeRecord vr : volumes.getVolumesList()) {
            if (!priceLookup.containsKey(vr.getServiceCode())) {
                throw new UnprocessableEntityException("Unpriced service: " + vr.getServiceCode()
                    + " — no suitable price list for this service");
            }
        }

        List<StatementLine> calculated = statement.getLines().stream()
            .filter(l -> l.getSource() == StatementLine.LineSource.CALCULATED)
            .toList();
        lineRepo.deleteAll(calculated);
        statement.getLines().removeAll(calculated);

        statement.setContractNo(contract.getContractNo());
        statement.setCustomerId(UUID.fromString(contract.getCustomerId()));
        statement.setCustomerName(contract.getCustomerName());
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

        Map<String, List<VolumeRecord>> volumesByService = volumes.getVolumesList().stream()
            .collect(Collectors.groupingBy(VolumeRecord::getServiceCode));
        int lineNo = statement.getLines().stream()
            .mapToInt(StatementLine::getLineNo).max().orElse(0) + 1;
        for (Map.Entry<String, List<VolumeRecord>> entry : volumesByService.entrySet()) {
            PriceLine priceLine = priceLookup.get(entry.getKey());
            BigDecimal totalQty = entry.getValue().stream()
                .map(vr -> BigDecimal.valueOf(vr.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal amount = totalQty.multiply(BigDecimal.valueOf(priceLine.getUnitPrice()))
                .setScale(2, RoundingMode.HALF_UP);
            StatementLine line = new StatementLine();
            line.setStatement(statement);
            line.setLineNo(lineNo++);
            line.setServiceCode(entry.getKey());
            line.setServiceName(priceLine.getServiceName());
            line.setUnit(priceLine.getUnit());
            line.setUnitPrice(BigDecimal.valueOf(priceLine.getUnitPrice()));
            line.setQuantity(totalQty);
            line.setAmount(amount);
            line.setSource(StatementLine.LineSource.CALCULATED);
            lineRepo.save(line);
            statement.getLines().add(line);
            for (VolumeRecord vr : entry.getValue()) {
                StatementLineVolume link = new StatementLineVolume();
                link.setLine(line);
                link.setVolumeRecordId(vr.getRecordNo());
                link.setRecordNo(vr.getRecordNo());
                link.setQuantity(BigDecimal.valueOf(vr.getQuantity()));
                lineVolumeRepo.save(link);
                line.getVolumeLinks().add(link);
            }
        }
        recomputeTotals(statement);
        statement.setStatus(PaymentStatement.StatementStatus.CALCULATED);
        statementRepo.saveAndFlush(statement);
    }

    @Transactional
    public StatementResponse recalculate(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        if (statement.getStatus() != PaymentStatement.StatementStatus.DRAFT) {
            throw new FailedPreconditionException("Only DRAFT statements can be recalculated");
        }

        if (statement.getAdjustsStatementId() != null) {
            // Adjustment statements are MANUAL-only deltas (PAY-05, seq-06 m39): there are no
            // volume pulls to refresh, so recalculation just recomputes totals over kept lines.
            recomputeTotals(statement);
            statement.setStatus(PaymentStatement.StatementStatus.CALCULATED);
            statementRepo.saveAndFlush(statement);
        } else {
            recalculateCalculatedLines(statement);
        }

        recordHistory(statement, PaymentStatement.StatementStatus.DRAFT,
            PaymentStatement.StatementStatus.CALCULATED, StatusHistory.TriggerKind.U, null);
        auditOutbox(statement, "statement.recalculated");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse revise(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));
        PaymentStatement.StatementStatus oldStatus = statement.getStatus();
        if (oldStatus != PaymentStatement.StatementStatus.REJECTED
            && oldStatus != PaymentStatement.StatementStatus.REVISION) {
            throw new FailedPreconditionException("Only REJECTED or REVISION statements can be revised");
        }
        statement.setStatus(PaymentStatement.StatementStatus.DRAFT);
        statementRepo.saveAndFlush(statement);
        recordHistory(statement, oldStatus, PaymentStatement.StatementStatus.DRAFT, StatusHistory.TriggerKind.U, null);
        auditOutbox(statement, "statement.revised");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse sendForSigning(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));
        if (statement.getStatus() != PaymentStatement.StatementStatus.APPROVED) {
            throw new FailedPreconditionException("Only APPROVED statements can be sent for signing (PAY-06)");
        }
        statement.setStatus(PaymentStatement.StatementStatus.SIGNING);
        statementRepo.saveAndFlush(statement);
        recordHistory(statement, PaymentStatement.StatementStatus.APPROVED,
            PaymentStatement.StatementStatus.SIGNING, StatusHistory.TriggerKind.U, null);

        UUID esignKey = UUID.randomUUID();
        OutboxEvent esignEvent = OutboxEvent.event(
            "esign.session_requested",
            "PAYMENT_STATEMENT",
            statement.getId(),
            String.format("{\"idempotency_key\":\"%s\",\"document_type\":\"PAYMENT_STATEMENT\","
                + "\"document_id\":\"%s\",\"document_no\":\"%s\",\"signer_name\":\"%s\"}",
                esignKey, statement.getId(), statement.getStatementNo(),
                escape(statement.getCustomerName() != null ? statement.getCustomerName() : ""))
        );
        outboxRepo.save(esignEvent);
        auditOutbox(statement, "statement.send_for_signing");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse publish(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));
        if (statement.getStatus() != PaymentStatement.StatementStatus.SIGNED) {
            throw new FailedPreconditionException("Only SIGNED statements can be published");
        }
        statement.setStatus(PaymentStatement.StatementStatus.ISSUED);
        statement.setIssuedAt(Instant.now());
        statement.setDueDate(computeDueDate(statement.getPaymentTerm(), statement.getPeriodEnd()));
        statementRepo.saveAndFlush(statement);
        recordHistory(statement, PaymentStatement.StatementStatus.SIGNED,
            PaymentStatement.StatementStatus.ISSUED, StatusHistory.TriggerKind.U, null);
        auditOutbox(statement, "statement.published");
        return toResponse(statement);
    }

    @Transactional
    public StatementResponse createAdjustment(String statementNo, AdjustmentRequest req) {
        PaymentStatement original = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));

        PaymentStatement.StatementStatus originalStatus = original.getStatus();
        if (originalStatus != PaymentStatement.StatementStatus.APPROVED
            && originalStatus != PaymentStatement.StatementStatus.SIGNED
            && originalStatus != PaymentStatement.StatementStatus.ISSUED) {
            throw new FailedPreconditionException(
                "Only APPROVED, SIGNED or ISSUED statements can have adjustments (PAY-05)");
        }
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new UnprocessableEntityException("Adjustment must have at least 1 line (PAY-04)");
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
        statementRepo.saveAndFlush(adjustment);

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
            adjustment.getLines().add(line);
            subtotal = subtotal.add(line.getAmount());
        }

        BigDecimal taxAmount = subtotal.multiply(adjustment.getVatRate())
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        adjustment.setSubtotal(subtotal);
        adjustment.setTaxAmount(taxAmount);
        adjustment.setTotalAmount(subtotal.add(taxAmount));
        if (adjustment.getTotalAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new UnprocessableEntityException("total_amount must be >= 0 (PAY-04)");
        }
        statementRepo.saveAndFlush(adjustment);

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
        // seq-06 m42: abandoning the document always traces why
        if (reason == null || reason.isBlank()) {
            throw new UnprocessableEntityException("Cancel reason is required (PAY-05)");
        }

        statement.setStatus(PaymentStatement.StatementStatus.CANCELLED);
        statementRepo.saveAndFlush(statement);

        recordHistory(statement, oldStatus, PaymentStatement.StatementStatus.CANCELLED, StatusHistory.TriggerKind.U, null);
        auditOutbox(statement, "statement.cancelled");
        return toResponse(statement);
    }

    @Transactional(readOnly = true)
    public WorkflowProgressResponse getWorkflowProgress(String statementNo) {
        PaymentStatement statement = statementRepo.findByStatementNo(statementNo)
            .orElseThrow(() -> new NotFoundException("Statement not found: " + statementNo));
        try {
            com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse instance =
                workflowClient.getInstanceByDocument("PAYMENT_STATEMENT", statement.getId().toString());
            // seq-03 m57: SUBMITTED plus anything but an IN_PROGRESS instance is the D4 dispatch
            // window — a stale terminal chain from the previous submission is discarded, not rendered.
            if (instance == null
                || (statement.getStatus() == PaymentStatement.StatementStatus.SUBMITTED
                    && !"IN_PROGRESS".equals(instance.getStatus()))) {
                return new WorkflowProgressResponse(statementNo, Map.of("status", "INITIALIZATION_PENDING"));
            }
            return new WorkflowProgressResponse(statementNo, instance);
        } catch (io.grpc.StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                // D4 dispatch window: SUBMITTED with no instance yet — render, don't retry (§5.1).
                return new WorkflowProgressResponse(statementNo, Map.of("status", "INITIALIZATION_PENDING"));
            }
            throw e;
        }
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

    /** PAY-01 window check shared by calculate and reconcile. */
    private static void assertContractInForce(GetContractResponse contract,
                                              LocalDate periodStart, LocalDate periodEnd) {
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
        int year = java.time.Year.now().getValue();
        int seq = statementRepo.nextStatementNoForYear(year);
        return String.format("PMT-%d-%04d", year, seq);
    }

    private void recomputeTotals(PaymentStatement statement) {
        BigDecimal subtotal = statement.getLines().stream()
            .map(StatementLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vatRate = statement.getVatRate() != null ? statement.getVatRate() : BigDecimal.ZERO;
        BigDecimal taxAmount = subtotal.multiply(vatRate)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        statement.setSubtotal(subtotal);
        statement.setTaxAmount(taxAmount);
        statement.setTotalAmount(subtotal.add(taxAmount));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void auditOutbox(PaymentStatement statement, String action) {
        // Serialized by AuditRecorder: no hand-built JSON, no "null"-string actor ids (finding 8).
        auditRecorder.record("PAYMENT_STATEMENT", statement.getId(), statement.getStatementNo(), action,
            null, statement.getStatus().name(), null, Map.of());
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
