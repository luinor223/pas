package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.domain.StatementLine;
import com.abclogistics.pas.billing.dto.AddLineRequest;
import com.abclogistics.pas.billing.dto.EditLineRequest;
import com.abclogistics.pas.billing.grpc.ContractGrpcClient;
import com.abclogistics.pas.billing.grpc.EsignGrpcClient;
import com.abclogistics.pas.billing.grpc.OperationsGrpcClient;
import com.abclogistics.pas.billing.grpc.PricingGrpcClient;
import com.abclogistics.pas.billing.grpc.WorkflowGrpcClient;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.StatementLineRepository;
import com.abclogistics.pas.billing.repository.StatementLineVolumeRepository;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Controlled edits must keep the statement totals in sync (PAY-04's submit check is vacuous
 * otherwise). No Spring, no Docker: the service is wired with mocked repositories.
 */
class StatementTotalsTest {

    private PaymentStatementRepository statements;
    private StatementLineRepository lines;
    private StatementService service;

    @BeforeEach
    void setUp() {
        statements = mock(PaymentStatementRepository.class);
        lines = mock(StatementLineRepository.class);
        StatementLineVolumeRepository links = mock(StatementLineVolumeRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        service = new StatementService(statements, lines, links, outbox,
                mock(ContractGrpcClient.class), mock(PricingGrpcClient.class),
                mock(OperationsGrpcClient.class), mock(WorkflowGrpcClient.class),
                mock(EsignGrpcClient.class), mock(com.abclogistics.pas.common.audit.AuditRecorder.class),
                mock(com.abclogistics.pas.billing.repository.StatusHistoryRepository.class));
    }

    @Test
    void editLineRecomputesSubtotalTaxAndTotal() {
        PaymentStatement stmt = calculatedStatement();
        when(statements.findByStatementNo("PMT-2026-0001")).thenReturn(Optional.of(stmt));

        service.editLine("PMT-2026-0001",
                new EditLineRequest(1, new BigDecimal("200.00"), new BigDecimal("10"), null, 0));

        // 10 x 200 = 2000 subtotal, 8% VAT = 160, total 2160
        assertThat(stmt.getSubtotal()).isEqualByComparingTo("2000.00");
        assertThat(stmt.getTaxAmount()).isEqualByComparingTo("160.00");
        assertThat(stmt.getTotalAmount()).isEqualByComparingTo("2160.00");
    }

    @Test
    void editLineOnCalculatedFlipsToDraftAsManual() {
        PaymentStatement stmt = calculatedStatement();
        when(statements.findByStatementNo("PMT-2026-0001")).thenReturn(Optional.of(stmt));

        var response = service.editLine("PMT-2026-0001",
                new EditLineRequest(1, new BigDecimal("200.00"), new BigDecimal("10"), "agreed adj", 0));

        assertThat(stmt.getStatus()).isEqualTo(PaymentStatement.StatementStatus.DRAFT);
        assertThat(stmt.getLines().get(0).getSource()).isEqualTo(StatementLine.LineSource.MANUAL);
        assertThat(response.status()).isEqualTo("DRAFT");
    }

    @Test
    void addLineAppendsManualLineAndRecomputes() {
        PaymentStatement stmt = calculatedStatement();
        when(statements.findByStatementNo("PMT-2026-0001")).thenReturn(Optional.of(stmt));
        when(lines.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addLine("PMT-2026-0001", new AddLineRequest("STORAGE", "Storage", "day",
                new BigDecimal("50.00"), new BigDecimal("4"), null, 0));

        // 1000 + 200 = 1200 subtotal, 96 tax, 1296 total
        assertThat(stmt.getLines()).hasSize(2);
        assertThat(stmt.getLines().get(1).getSource()).isEqualTo(StatementLine.LineSource.MANUAL);
        assertThat(stmt.getSubtotal()).isEqualByComparingTo("1200.00");
        assertThat(stmt.getTotalAmount()).isEqualByComparingTo("1296.00");
        assertThat(stmt.getStatus()).isEqualTo(PaymentStatement.StatementStatus.DRAFT);
    }

    @Test
    void staleVersionLosesLoudly() {
        PaymentStatement stmt = calculatedStatement();
        when(statements.findByStatementNo("PMT-2026-0001")).thenReturn(Optional.of(stmt));

        assertThatThrownBy(() -> service.editLine("PMT-2026-0001",
                new EditLineRequest(1, new BigDecimal("200.00"), new BigDecimal("10"), null, 7)))
                .isInstanceOf(com.abclogistics.pas.billing.error.UnprocessableEntityException.class)
                .hasMessageContaining("Stale statement version");
    }

    @Test
    void editIsRejectedOutsideDraftAndCalculated() {
        PaymentStatement stmt = calculatedStatement();
        stmt.setStatus(PaymentStatement.StatementStatus.SIGNED);
        when(statements.findByStatementNo("PMT-2026-0001")).thenReturn(Optional.of(stmt));

        assertThatThrownBy(() -> service.editLine("PMT-2026-0001",
                new EditLineRequest(1, new BigDecimal("1.00"), new BigDecimal("1"), null, 0)))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("DRAFT or CALCULATED");
    }

    private static PaymentStatement calculatedStatement() {
        PaymentStatement stmt = new PaymentStatement();
        stmt.setStatementNo("PMT-2026-0001");
        stmt.setVatRate(new BigDecimal("8.00"));
        stmt.setSubtotal(new BigDecimal("1000.00"));
        stmt.setTaxAmount(new BigDecimal("80.00"));
        stmt.setTotalAmount(new BigDecimal("1080.00"));
        stmt.setStatus(PaymentStatement.StatementStatus.CALCULATED);
        StatementLine line = new StatementLine();
        line.setStatement(stmt);
        line.setLineNo(1);
        line.setServiceCode("CNT");
        line.setServiceName("Container handling");
        line.setUnit("TEU");
        line.setUnitPrice(new BigDecimal("100.00"));
        line.setQuantity(new BigDecimal("10"));
        line.setAmount(new BigDecimal("1000.00"));
        line.setSource(StatementLine.LineSource.CALCULATED);
        stmt.getLines().add(line);
        return stmt;
    }
}
