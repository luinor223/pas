package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.listener.BillingEventListener;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.ProcessedEventRepository;
import com.abclogistics.pas.billing.repository.StatusHistoryRepository;
import com.abclogistics.pas.common.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
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
 * Single-consumer Kafka intake (one group per service, registry §4): dedup on the event ID first
 * (same-ID redelivery skips quietly), then anything anomalous — no document, unknown document,
 * unknown outcome, illegal state — throws to the DLT loudly instead of acking silently.
 * No Spring, no Docker.
 */
class BillingListenersTest {

    private PaymentStatementRepository statements;
    private ProcessedEventRepository processed;
    private StatusHistoryRepository history;
    private AuditRecorder audit;
    private BillingEventListener listener;

    @BeforeEach
    void setUp() {
        statements = mock(PaymentStatementRepository.class);
        processed = mock(ProcessedEventRepository.class);
        history = mock(StatusHistoryRepository.class);
        audit = mock(AuditRecorder.class);
        listener = new BillingEventListener(statements, processed, history, audit, new ObjectMapper());
    }

    @Test
    void unrelatedEventTypesAreIgnored() {
        listener.onEvent("{}", "workflow.instance_started", "PAYMENT_STATEMENT",
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        verify(processed, never()).save(any());
        verify(statements, never()).findById(any());
    }

    @Test
    void foreignDocumentTypesAreIgnored() {
        listener.onEvent("{}", "workflow.completed", "CONTRACT",
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        verify(processed, never()).save(any());
    }

    @Test
    void workflowCompletedApprovesAndAudits() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        PaymentStatement stmt = statement(PaymentStatement.StatementStatus.SUBMITTED);
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.of(stmt));

        listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"outcome\":\"APPROVED\",\"instance_id\":\"" + UUID.randomUUID() + "\"}",
                "workflow.completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString());

        assertThat(stmt.getStatus()).isEqualTo(PaymentStatement.StatementStatus.APPROVED);
        verify(processed).save(any());
        verify(history).save(any());
        verify(audit).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void workflowCompletedRevisionRequestedMapsToRevision() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        PaymentStatement stmt = statement(PaymentStatement.StatementStatus.SUBMITTED);
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.of(stmt));

        listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"outcome\":\"REVISION_REQUESTED\"}",
                "workflow.completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString());

        assertThat(stmt.getStatus()).isEqualTo(PaymentStatement.StatementStatus.REVISION);
    }

    @Test
    void duplicateEventIsDedupedBeforeAnyWork() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(eventId)).thenReturn(true);

        listener.onEvent("{\"outcome\":\"APPROVED\"}",
                "workflow.completed", "PAYMENT_STATEMENT", eventId.toString(), UUID.randomUUID().toString());

        verify(statements, never()).findById(any());
        verify(history, never()).save(any());
    }

    @Test
    void completedForNonSubmittedThrowsToDlt() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        PaymentStatement stmt = statement(PaymentStatement.StatementStatus.APPROVED);
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.of(stmt));

        // a NEW event ID in a terminal state is anomalous — loud DLT, never a silent ack
        assertThatThrownBy(() -> listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"outcome\":\"REJECTED\"}",
                "workflow.completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUBMITTED");
        verify(history, never()).save(any());
    }

    @Test
    void completedForUnknownStatementThrowsToDlt() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"outcome\":\"APPROVED\"}",
                "workflow.completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown statement");
        verify(processed, never()).save(any());
    }

    @Test
    void unknownWorkflowOutcomeIsRefusedNotParkedSilently() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.of(statement(PaymentStatement.StatementStatus.SUBMITTED)));

        assertThatThrownBy(() -> listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"outcome\":\"MAYBE\"}",
                "workflow.completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAYBE");
    }

    @Test
    void esignSignedFlipsToSignedPay07() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        PaymentStatement stmt = statement(PaymentStatement.StatementStatus.SIGNING);
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.of(stmt));

        listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"result\":\"SIGNED\",\"session_id\":\"" + UUID.randomUUID() + "\"}",
                "esign.session_completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString());

        assertThat(stmt.getStatus()).isEqualTo(PaymentStatement.StatementStatus.SIGNED);
        verify(history).save(any());
        verify(audit).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void esignFailedFlipsToRevisionPay07() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        PaymentStatement stmt = statement(PaymentStatement.StatementStatus.SIGNING);
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.of(stmt));

        listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"result\":\"FAILED\"}",
                "esign.session_completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString());

        assertThat(stmt.getStatus()).isEqualTo(PaymentStatement.StatementStatus.REVISION);
    }

    @Test
    void esignForNonSigningThrowsToDlt() {
        UUID docId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(eventId)).thenReturn(false);
        when(statements.findById(docId)).thenReturn(Optional.of(statement(PaymentStatement.StatementStatus.SIGNED)));

        assertThatThrownBy(() -> listener.onEvent(
                "{\"document_id\":\"" + docId + "\",\"result\":\"SIGNED\"}",
                "esign.session_completed", "PAYMENT_STATEMENT", eventId.toString(), docId.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGNING");
    }

    private static PaymentStatement statement(PaymentStatement.StatementStatus status) {
        PaymentStatement stmt = new PaymentStatement();
        stmt.setStatementNo("PMT-2026-x");
        stmt.setContractId(UUID.randomUUID());
        stmt.setContractNo("CTR-2026-0001");
        stmt.setPeriodCode("2026-06");
        stmt.setPeriodStart(LocalDate.of(2026, 6, 1));
        stmt.setPeriodEnd(LocalDate.of(2026, 6, 30));
        stmt.setStatus(status);
        return stmt;
    }
}
