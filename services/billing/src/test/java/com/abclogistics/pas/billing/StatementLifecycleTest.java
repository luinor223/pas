package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.domain.StatementLine;
import com.abclogistics.pas.billing.dto.AdjustmentRequest;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import com.abclogistics.pas.billing.client.ContractGrpcClient;
import com.abclogistics.pas.billing.client.OperationsGrpcClient;
import com.abclogistics.pas.billing.client.PricingGrpcClient;
import com.abclogistics.pas.billing.client.WorkflowGrpcClient;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.StatementLineRepository;
import com.abclogistics.pas.billing.repository.StatementLineVolumeRepository;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registry §9 PAYMENT_STATEMENT edges that previously had no REST path: revise, send-sign,
 * publish, plus the PAY-05 adjustment scope. No Spring, no Docker.
 */
class StatementLifecycleTest {

    private PaymentStatementRepository statements;
    private OutboxRepository outbox;
    private com.abclogistics.pas.common.audit.AuditRecorder audit;
    private StatementService service;

    @BeforeEach
    void setUp() {
        statements = mock(PaymentStatementRepository.class);
        StatementLineRepository lines = mock(StatementLineRepository.class);
        StatementLineVolumeRepository links = mock(StatementLineVolumeRepository.class);
        outbox = mock(OutboxRepository.class);
        audit = mock(com.abclogistics.pas.common.audit.AuditRecorder.class);
        service = new StatementService(statements, lines, links, outbox,
                mock(ContractGrpcClient.class), mock(PricingGrpcClient.class),
                mock(OperationsGrpcClient.class), mock(WorkflowGrpcClient.class),
                audit,
                new com.abclogistics.pas.billing.service.StatusTransitionService(mock(com.abclogistics.pas.billing.repository.StatusHistoryRepository.class)),
                mock(tools.jackson.databind.ObjectMapper.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        when(statements.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void reviseReturnsRejectedAndRevisionToDraft() {
        for (PaymentStatement.StatementStatus from : List.of(
                PaymentStatement.StatementStatus.REJECTED, PaymentStatement.StatementStatus.REVISION)) {
            PaymentStatement stmt = bare(from);
            when(statements.findByStatementNo("PMT-2026-0007")).thenReturn(Optional.of(stmt));

            var response = service.revise("PMT-2026-0007");

            assertThat(response.status()).isEqualTo("DRAFT");
            assertThat(stmt.getStatusHistory()).hasSize(1);
        }
    }

    @Test
    void reviseIsRejectedFromSubmitted() {
        PaymentStatement stmt = bare(PaymentStatement.StatementStatus.SUBMITTED);
        when(statements.findByStatementNo("PMT-2026-0007")).thenReturn(Optional.of(stmt));

        assertThatThrownBy(() -> service.revise("PMT-2026-0007"))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("cannot move");
    }

    @Test
    void sendForSigningWritesEsignOutboxInSameCall() {
        PaymentStatement stmt = bare(PaymentStatement.StatementStatus.APPROVED);
        when(statements.findByStatementNo("PMT-2026-0009")).thenReturn(Optional.of(stmt));

        var response = service.sendForSigning("PMT-2026-0009");

        assertThat(response.status()).isEqualTo("SIGNING");
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> "esign.session_requested".equals(e.getEventType()));
        verify(audit).record(any(), any(), any(), any(), any(), any(), any(), any());
        // M5: the centralized trail keeps before AND after
        verify(audit).record(eq("PAYMENT_STATEMENT"), any(), any(), eq("statement.send_for_signing"),
                eq("APPROVED"), eq("SIGNING"), any(), any());
    }

    @Test
    void sendForSigningRequiresApprovedPay06() {
        PaymentStatement stmt = bare(PaymentStatement.StatementStatus.RECONCILED);
        when(statements.findByStatementNo("PMT-2026-0009")).thenReturn(Optional.of(stmt));

        assertThatThrownBy(() -> service.sendForSigning("PMT-2026-0009"))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("cannot move");
    }

    @Test
    void publishComputesDueDateFromPaymentTerm() {
        PaymentStatement stmt = bare(PaymentStatement.StatementStatus.SIGNED);
        stmt.setPaymentTerm("NET 30");
        stmt.setPeriodEnd(LocalDate.of(2026, 6, 30));
        when(statements.findByStatementNo("PMT-2026-0011")).thenReturn(Optional.of(stmt));

        var response = service.publish("PMT-2026-0011");

        assertThat(response.status()).isEqualTo("ISSUED");
        assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 7, 30));
    }

    @Test
    void publishIsRejectedUnlessSigned() {
        PaymentStatement stmt = bare(PaymentStatement.StatementStatus.APPROVED);
        when(statements.findByStatementNo("PMT-2026-0011")).thenReturn(Optional.of(stmt));

        assertThatThrownBy(() -> service.publish("PMT-2026-0011"))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("cannot move");
    }

    @Test
    void adjustmentsAllowedFromApprovedSignedAndIssuedPay05() {
        for (PaymentStatement.StatementStatus from : List.of(
                PaymentStatement.StatementStatus.APPROVED,
                PaymentStatement.StatementStatus.SIGNED,
                PaymentStatement.StatementStatus.ISSUED)) {
            PaymentStatement original = bare(from);
            original.setVatRate(new BigDecimal("8.00"));
            when(statements.findByStatementNo("PMT-2026-0013")).thenReturn(Optional.of(original));

            var response = service.createAdjustment("PMT-2026-0013", adjustment());

            assertThat(response.status()).isEqualTo("DRAFT");
            assertThat(response.adjustsStatementId()).isEqualTo(original.getId());
            assertThat(original.getStatus()).isEqualTo(from); // original stays immutable
        }
    }

    @Test
    void adjustmentIsRejectedBeforeApproval() {
        PaymentStatement original = bare(PaymentStatement.StatementStatus.RECONCILED);
        when(statements.findByStatementNo("PMT-2026-0013")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.createAdjustment("PMT-2026-0013", adjustment()))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("PAY-05");
    }

    @Test
    void adjustmentRequiresAtLeastOneLinePay04() {
        PaymentStatement original = bare(PaymentStatement.StatementStatus.ISSUED);
        original.setVatRate(new BigDecimal("8.00"));
        when(statements.findByStatementNo("PMT-2026-0013")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.createAdjustment("PMT-2026-0013",
                new AdjustmentRequest("oops", List.of())))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, ex -> {
                    assertThat(ex.getPublicCode()).isEqualTo("ADJUSTMENT_LINES_REQUIRED");
                    assertThat(ex.getPublicMessage()).isEqualTo("Add at least one line to the adjustment.");
                    assertThat(ex.getMessage()).contains("PAY-04");
                });
    }

    @Test
    void adjustmentRejectsANegativeTotalWithoutPublishingInternalNames() {
        PaymentStatement original = bare(PaymentStatement.StatementStatus.ISSUED);
        original.setVatRate(new BigDecimal("8.00"));
        when(statements.findByStatementNo("PMT-2026-0013")).thenReturn(Optional.of(original));
        AdjustmentRequest request = new AdjustmentRequest("negative", List.of(
                new AdjustmentRequest.AdjustmentLineInput("CNT", "Container handling", "TEU",
                        new BigDecimal("-100.00"), new BigDecimal("2"), null)));

        assertThatThrownBy(() -> service.createAdjustment("PMT-2026-0013", request))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, ex -> {
                    assertThat(ex.getPublicCode()).isEqualTo("ADJUSTMENT_TOTAL_INVALID");
                    assertThat(ex.getPublicMessage()).isEqualTo("The adjustment total cannot be negative.");
                    assertThat(ex.getMessage()).contains("total_amount", "PAY-04");
                });
    }

    private static PaymentStatement bare(PaymentStatement.StatementStatus status) {
        PaymentStatement stmt = new PaymentStatement();
        stmt.setStatementNo("PMT-2026-x");
        stmt.setContractId(UUID.randomUUID());
        stmt.setContractNo("CTR-2026-0001");
        stmt.setPeriodCode("2026-06");
        stmt.setPeriodStart(LocalDate.of(2026, 6, 1));
        stmt.setPeriodEnd(LocalDate.of(2026, 6, 30));
        stmt.setVatRate(new BigDecimal("8.00"));
        stmt.setSubtotal(BigDecimal.ZERO);
        stmt.setTaxAmount(BigDecimal.ZERO);
        stmt.setTotalAmount(BigDecimal.ZERO);
        stmt.setStatus(status);
        StatementLine line = new StatementLine();
        line.setStatement(stmt);
        line.setLineNo(1);
        line.setServiceCode("CNT");
        line.setUnitPrice(BigDecimal.ONE);
        line.setQuantity(BigDecimal.ONE);
        line.setAmount(BigDecimal.ONE);
        stmt.getLines().add(line);
        return stmt;
    }

    private static AdjustmentRequest adjustment() {
        return new AdjustmentRequest("correct qty", List.of(
                new AdjustmentRequest.AdjustmentLineInput("CNT", "Container handling", "TEU",
                        new BigDecimal("100.00"), new BigDecimal("2"), null)));
    }
}
