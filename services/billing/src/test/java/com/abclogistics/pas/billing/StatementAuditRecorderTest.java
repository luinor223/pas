package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.grpc.ContractGrpcClient;
import com.abclogistics.pas.billing.grpc.EsignGrpcClient;
import com.abclogistics.pas.billing.grpc.OperationsGrpcClient;
import com.abclogistics.pas.billing.grpc.PricingGrpcClient;
import com.abclogistics.pas.billing.grpc.WorkflowGrpcClient;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.StatementLineRepository;
import com.abclogistics.pas.billing.repository.StatementLineVolumeRepository;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementAuditRecorderTest {

    @Test
    void reconciliationUsesSharedAuditRecorderWithStatementNumber() throws Exception {
        PaymentStatementRepository statements = mock(PaymentStatementRepository.class);
        OperationsGrpcClient operations = mock(OperationsGrpcClient.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        StatementService service = new StatementService(
                statements,
                mock(StatementLineRepository.class),
                mock(StatementLineVolumeRepository.class),
                mock(OutboxRepository.class),
                mock(ContractGrpcClient.class),
                mock(PricingGrpcClient.class),
                operations,
                mock(WorkflowGrpcClient.class),
                mock(EsignGrpcClient.class),
                audit,
                new com.abclogistics.pas.billing.service.StatusTransitionService(
                        mock(com.abclogistics.pas.billing.repository.StatusHistoryRepository.class)),
                mock(tools.jackson.databind.ObjectMapper.class));

        UUID statementId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        PaymentStatement statement = new PaymentStatement();
        setId(statement, statementId);
        statement.setStatementNo("PMT-2026-0042");
        statement.setContractId(contractId);
        statement.setContractNo("CTR-2026-0010");
        statement.setPeriodCode("2026-08");
        statement.setStatus(PaymentStatement.StatementStatus.CALCULATED);
        when(statements.findByStatementNo("PMT-2026-0042")).thenReturn(Optional.of(statement));
        when(operations.listVolumes(contractId.toString(), "2026-08"))
                .thenReturn(ListVolumesResponse.newBuilder().setPeriodState("LOCKED").build());

        service.reconcile("PMT-2026-0042");

        verify(audit).record(
                eq("PAYMENT_STATEMENT"), eq(statementId), eq("PMT-2026-0042"),
                eq("statement.reconciled"), eq("CALCULATED"), eq("RECONCILED"), eq(null), eq(Map.of()));
    }

    private static void setId(PaymentStatement statement, UUID id) throws Exception {
        Field field = PaymentStatement.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(statement, id);
    }
}
