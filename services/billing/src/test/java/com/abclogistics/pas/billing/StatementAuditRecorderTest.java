package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.client.ContractGrpcClient;
import com.abclogistics.pas.billing.client.OperationsGrpcClient;
import com.abclogistics.pas.billing.client.PricingGrpcClient;
import com.abclogistics.pas.billing.client.WorkflowGrpcClient;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.StatementLineRepository;
import com.abclogistics.pas.billing.repository.StatementLineVolumeRepository;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.pricing.grpc.GetEffectivePriceListResponse;
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
        ContractGrpcClient contracts = mock(ContractGrpcClient.class);
        PricingGrpcClient pricing = mock(PricingGrpcClient.class);
        OperationsGrpcClient operations = mock(OperationsGrpcClient.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        StatementService service = new StatementService(
                statements,
                mock(StatementLineRepository.class),
                mock(StatementLineVolumeRepository.class),
                mock(OutboxRepository.class),
                contracts,
                pricing,
                operations,
                mock(WorkflowGrpcClient.class),
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
        statement.setPeriodStart(java.time.LocalDate.parse("2026-08-01"));
        statement.setPeriodEnd(java.time.LocalDate.parse("2026-08-31"));
        statement.setPriceListNo("PRC-2026-0001");
        statement.setPriceListVersionNo(3);
        statement.setStatus(PaymentStatement.StatementStatus.CALCULATED);
        when(statements.findByStatementNo("PMT-2026-0042")).thenReturn(Optional.of(statement));
        when(operations.listVolumes(contractId.toString(), "2026-08"))
                .thenReturn(ListVolumesResponse.newBuilder()
                        .setPeriodState("LOCKED").setPeriodEnd("2026-08-31").build());
        when(contracts.getContract(contractId.toString())).thenReturn(GetContractResponse.newBuilder()
                .setCustomerId("customer-1").setServiceGroup("STEVEDORING")
                .setValidFrom("2026-01-01").setValidTo("2026-12-31").build());
        when(pricing.getEffectivePriceList(
                contractId.toString(), "customer-1", "STEVEDORING", "2026-08-31"))
                .thenReturn(GetEffectivePriceListResponse.newBuilder()
                        .setPriceListNo("PRC-2026-0001").setVersionNo(3).build());

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
