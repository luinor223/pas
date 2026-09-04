package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.domain.StatementLine;
import com.abclogistics.pas.billing.domain.StatementLineVolume;
import com.abclogistics.pas.billing.error.UnprocessableEntityException;
import com.abclogistics.pas.billing.grpc.ContractGrpcClient;
import com.abclogistics.pas.billing.grpc.OperationsGrpcClient;
import com.abclogistics.pas.billing.grpc.PricingGrpcClient;
import com.abclogistics.pas.billing.grpc.WorkflowGrpcClient;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.StatementLineRepository;
import com.abclogistics.pas.billing.repository.StatementLineVolumeRepository;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.operations.grpc.VolumeRecord;
import com.abclogistics.pas.pricing.grpc.GetEffectivePriceListResponse;
import com.abclogistics.pas.pricing.grpc.PriceLine;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review follow-ups: recalculate preserves MANUAL lines (seq-06 m20), adjustments re-enter the
 * build path (m39), reconcile drift checks (m24), submit mapping gate (m26, adjustments exempt),
 * progress discards stale terminal instances (seq-03 m57), cancel reason mandatory (m42).
 * No Spring, no Docker.
 */
class StatementRecalculateTest {

    private PaymentStatementRepository statements;
    private ContractGrpcClient contractClient;
    private PricingGrpcClient pricingClient;
    private OperationsGrpcClient operationsClient;
    private WorkflowGrpcClient workflowClient;
    private StatementService service;

    @BeforeEach
    void setUp() {
        statements = mock(PaymentStatementRepository.class);
        contractClient = mock(ContractGrpcClient.class);
        pricingClient = mock(PricingGrpcClient.class);
        operationsClient = mock(OperationsGrpcClient.class);
        workflowClient = mock(WorkflowGrpcClient.class);
        service = new StatementService(statements, mock(StatementLineRepository.class),
                mock(StatementLineVolumeRepository.class), mock(OutboxRepository.class),
                contractClient, pricingClient, operationsClient, workflowClient,
                mock(AuditRecorder.class),
                new com.abclogistics.pas.billing.service.StatusTransitionService(mock(com.abclogistics.pas.billing.repository.StatusHistoryRepository.class)),
                mock(tools.jackson.databind.ObjectMapper.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        when(statements.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recalculatePreservesManualLines() {
        PaymentStatement stmt = draftStatement();
        stmt.getLines().add(calculatedLine(stmt, 1, "VOL-1", new BigDecimal("10")));
        stmt.getLines().add(manualLine(stmt, 2));
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        stubPulls("12");

        var response = service.recalculate("PMT-x");

        assertThat(response.status()).isEqualTo("CALCULATED");
        assertThat(stmt.getLines()).hasSize(2);
        assertThat(stmt.getLines().stream()
                .filter(l -> l.getSource() == StatementLine.LineSource.MANUAL))
                .hasSize(1)
                .allMatch(l -> l.getServiceCode().equals("MAN") && l.getAmount().compareTo(new BigDecimal("50.00")) == 0);
        // refreshed CALCULATED: (12 + 2) x 100 = 1400; totals cover both sources: 1450 + 116 tax
        assertThat(stmt.getSubtotal()).isEqualByComparingTo("1450.00");
        assertThat(stmt.getTotalAmount()).isEqualByComparingTo("1566.00");
    }

    @Test
    void recalculateAdjustmentSkipsVolumePulls() {
        PaymentStatement adjustment = draftStatement();
        adjustment.setAdjustsStatementId(UUID.randomUUID());
        adjustment.getLines().add(manualLine(adjustment, 1));
        when(statements.findByStatementNo("PMT-a")).thenReturn(Optional.of(adjustment));

        var response = service.recalculate("PMT-a");

        assertThat(response.status()).isEqualTo("CALCULATED");
        assertThat(adjustment.getLines()).hasSize(1);
        verify(operationsClient, never()).listVolumes(any(), any());
        verify(pricingClient, never()).getEffectivePriceList(any(), any(), any(), any());
    }

    @Test
    void reconcileRejectsQuantityDrift() {
        PaymentStatement stmt = calculatedStatement();
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        stubPulls("8"); // billed 10, now 8: a post-lock edit

        assertThatThrownBy(() -> service.reconcile("PMT-x"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("VOL-1");
    }

    @Test
    void reconcileRejectsPriceVersionDrift() {
        PaymentStatement stmt = calculatedStatement();
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        stubPulls("10");
        when(pricingClient.getEffectivePriceList(any(), any(), any(), any()))
                .thenReturn(priceResponse(4)); // snapshot holds v3

        assertThatThrownBy(() -> service.reconcile("PMT-x"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("drifted");
    }

    @Test
    void reconcileIgnoresManualLinkDrift() {
        // a MANUAL correction keeps its original link; only CALCULATED links gate reconciliation
        PaymentStatement stmt = calculatedStatement();
        StatementLine manual = manualLine(stmt, 2);
        StatementLineVolume stale = new StatementLineVolume();
        stale.setLine(manual);
        stale.setVolumeRecordId("VOL-9");
        stale.setRecordNo("VOL-9");
        stale.setQuantity(new BigDecimal("99"));
        manual.getVolumeLinks().add(stale);
        stmt.getLines().add(manual);
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        stubPulls("10");

        assertThat(service.reconcile("PMT-x").status()).isEqualTo("RECONCILED");
    }

    @Test
    void progressOnNeverSubmittedRendersLocalStatus() {
        PaymentStatement stmt = draftStatement(); // DRAFT, never submitted
        setId(stmt, UUID.randomUUID());
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        when(workflowClient.getInstanceByDocument(any(), any())).thenThrow(
                new io.grpc.StatusRuntimeException(io.grpc.Status.NOT_FOUND));

        var progress = service.getWorkflowProgress("PMT-x");

        assertThat(progress.workflowInstance()).isEqualTo(Map.of("status", "DRAFT"));
    }

    @Test
    void reconcileSkipsUpstreamChecksForAdjustments() {
        PaymentStatement adjustment = calculatedStatement();
        adjustment.setAdjustsStatementId(UUID.randomUUID());
        when(statements.findByStatementNo("PMT-a")).thenReturn(Optional.of(adjustment));

        assertThat(service.reconcile("PMT-a").status()).isEqualTo("RECONCILED");
        verify(operationsClient, never()).listVolumes(any(), any());
    }

    @Test
    void submitRejectsUnmappedVolumes() {
        PaymentStatement stmt = reconStatement();
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        stubPulls("10"); // VOL-1 linked, VOL-2 not

        assertThatThrownBy(() -> service.submit("PMT-x"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("VOL-2026-2");
    }

    @Test
    void submitSkipsMappingGateForAdjustments() {
        PaymentStatement adjustment = reconStatement();
        adjustment.setAdjustsStatementId(UUID.randomUUID());
        when(statements.findByStatementNo("PMT-a")).thenReturn(Optional.of(adjustment));

        assertThat(service.submit("PMT-a").status()).isEqualTo("SUBMITTED");
        verify(operationsClient, never()).listVolumes(any(), any());
    }

    @Test
    void progressDiscardsStaleTerminalInstance() {
        PaymentStatement stmt = draftStatement();
        stmt.setStatus(PaymentStatement.StatementStatus.SUBMITTED);
        setId(stmt, UUID.randomUUID());
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        when(workflowClient.getInstanceByDocument(any(), any())).thenReturn(
                GetInstanceByDocumentResponse.newBuilder().setInstanceId("old").setStatus("REJECTED").build());

        var progress = service.getWorkflowProgress("PMT-x");

        assertThat(progress.workflowInstance()).isEqualTo(Map.of("status", "INITIALIZATION_PENDING"));
    }

    @Test
    void progressReturnsLiveInstance() {
        PaymentStatement stmt = draftStatement();
        stmt.setStatus(PaymentStatement.StatementStatus.SUBMITTED);
        setId(stmt, UUID.randomUUID());
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));
        var live = GetInstanceByDocumentResponse.newBuilder().setInstanceId("new").setStatus("IN_PROGRESS").build();
        when(workflowClient.getInstanceByDocument(any(), any())).thenReturn(live);

        assertThat(service.getWorkflowProgress("PMT-x").workflowInstance()).isEqualTo(live);
    }

    @Test
    void cancelRequiresReason() {
        PaymentStatement stmt = draftStatement();
        stmt.setStatus(PaymentStatement.StatementStatus.APPROVED);
        when(statements.findByStatementNo("PMT-x")).thenReturn(Optional.of(stmt));

        assertThatThrownBy(() -> service.cancelStatement("PMT-x", "  "))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("reason");
        assertThat(service.cancelStatement("PMT-x", "duplicate issue").status()).isEqualTo("CANCELLED");
    }

    private static void setId(PaymentStatement stmt, UUID id) {
        try {
            var field = PaymentStatement.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(stmt, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubPulls(String qty) {
        when(contractClient.getContract(any())).thenReturn(GetContractResponse.newBuilder()
                .setContractNo("CTR-2026-0001").setStatus("ACTIVE")
                .setValidFrom("2026-01-01").setValidTo("2026-12-31")
                .setServiceGroup("STEVEDORING").setVatRate(8.0).setPaymentTerm("NET 30")
                .setCustomerId(UUID.randomUUID().toString()).setCustomerName("ACME").setCurrency("VND").build());
        when(operationsClient.listVolumes(any(), any())).thenReturn(ListVolumesResponse.newBuilder()
                .setPeriodState("LOCKED").setPeriodStart("2026-06-01").setPeriodEnd("2026-06-30")
                .addVolumes(VolumeRecord.newBuilder()
                        .setRecordNo("VOL-1").setServiceCode("CNT").setUnit("TEU")
                        .setQuantity(Double.parseDouble(qty)).build())
                .addVolumes(VolumeRecord.newBuilder()
                        .setRecordNo("VOL-2026-2").setServiceCode("CNT").setUnit("TEU")
                        .setQuantity(2).build())
                .build());
        when(pricingClient.getEffectivePriceList(any(), any(), any(), any())).thenReturn(priceResponse(3));
    }

    private static GetEffectivePriceListResponse priceResponse(int versionNo) {
        return GetEffectivePriceListResponse.newBuilder()
                .setPriceListNo("PRC-2026-0001").setVersionNo(versionNo)
                .setVersionId(UUID.randomUUID().toString())
                .addLines(PriceLine.newBuilder()
                        .setServiceCode("CNT").setServiceName("Container handling")
                        .setUnit("TEU").setUnitPrice(100.0).build())
                .build();
    }

    private static PaymentStatement draftStatement() {
        PaymentStatement stmt = new PaymentStatement();
        stmt.setStatementNo("PMT-x");
        stmt.setContractId(UUID.randomUUID());
        stmt.setContractNo("CTR-2026-0001");
        stmt.setPeriodCode("2026-06");
        stmt.setPeriodStart(LocalDate.of(2026, 6, 1));
        stmt.setPeriodEnd(LocalDate.of(2026, 6, 30));
        stmt.setVatRate(new BigDecimal("8.00"));
        stmt.setSubtotal(BigDecimal.ZERO);
        stmt.setTaxAmount(BigDecimal.ZERO);
        stmt.setTotalAmount(BigDecimal.ZERO);
        stmt.setStatus(PaymentStatement.StatementStatus.DRAFT);
        return stmt;
    }

    private static PaymentStatement calculatedStatement() {
        PaymentStatement stmt = draftStatement();
        stmt.setPriceListNo("PRC-2026-0001");
        stmt.setPriceListVersionNo(3);
        stmt.setStatus(PaymentStatement.StatementStatus.CALCULATED);
        stmt.getLines().add(calculatedLine(stmt, 1, "VOL-1", new BigDecimal("10")));
        return stmt;
    }

    private static PaymentStatement reconStatement() {
        PaymentStatement stmt = calculatedStatement();
        stmt.setSubtotal(new BigDecimal("1000.00"));
        stmt.setTaxAmount(new BigDecimal("80.00"));
        stmt.setTotalAmount(new BigDecimal("1080.00"));
        stmt.setStatus(PaymentStatement.StatementStatus.RECONCILED);
        return stmt;
    }

    private static StatementLine calculatedLine(PaymentStatement stmt, int lineNo, String recordNo, BigDecimal qty) {
        StatementLine line = new StatementLine();
        line.setStatement(stmt);
        line.setLineNo(lineNo);
        line.setServiceCode("CNT");
        line.setServiceName("Container handling");
        line.setUnit("TEU");
        line.setUnitPrice(new BigDecimal("100.00"));
        line.setQuantity(qty);
        line.setAmount(new BigDecimal("100.00").multiply(qty).setScale(2));
        line.setSource(StatementLine.LineSource.CALCULATED);
        StatementLineVolume link = new StatementLineVolume();
        link.setLine(line);
        link.setVolumeRecordId(recordNo);
        link.setRecordNo(recordNo);
        link.setQuantity(qty);
        line.getVolumeLinks().add(link);
        return line;
    }

    private static StatementLine manualLine(PaymentStatement stmt, int lineNo) {
        StatementLine line = new StatementLine();
        line.setStatement(stmt);
        line.setLineNo(lineNo);
        line.setServiceCode("MAN");
        line.setServiceName("Manual correction");
        line.setUnit("set");
        line.setUnitPrice(new BigDecimal("50.00"));
        line.setQuantity(BigDecimal.ONE);
        line.setAmount(new BigDecimal("50.00"));
        line.setSource(StatementLine.LineSource.MANUAL);
        return line;
    }
}
