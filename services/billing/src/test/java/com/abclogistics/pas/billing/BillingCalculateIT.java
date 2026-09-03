package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.dto.AdjustmentRequest;
import com.abclogistics.pas.billing.dto.CalculateStatementRequest;
import com.abclogistics.pas.billing.dto.EditLineRequest;
import com.abclogistics.pas.billing.dto.StatementResponse;
import com.abclogistics.pas.billing.error.UnprocessableEntityException;
import com.abclogistics.pas.billing.grpc.ContractGrpcClient;
import com.abclogistics.pas.billing.grpc.OperationsGrpcClient;
import com.abclogistics.pas.billing.grpc.PricingGrpcClient;
import com.abclogistics.pas.billing.grpc.WorkflowGrpcClient;
import com.abclogistics.pas.billing.listener.EsignEventListener;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.ProcessedEventRepository;
import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.grpc.GetContractResponse;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.operations.grpc.VolumeRecord;
import com.abclogistics.pas.pricing.grpc.GetEffectivePriceListResponse;
import com.abclogistics.pas.pricing.grpc.PriceLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Session 6 Done criteria against real Postgres/Flyway (V1–V4): PAY-01/02/03/04 snapshots and
 * gates, the reconcile→submit→approve→sign→issue chain, PAY-07, and PAY-05 adjustments.
 * Upstream services are stubbed at the gRPC client boundary.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class BillingCalculateIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_billing").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("outbox.relay.enabled", () -> "false");
    }

    @MockitoBean ContractGrpcClient contractClient;
    @MockitoBean PricingGrpcClient pricingClient;
    @MockitoBean OperationsGrpcClient operationsClient;
    @MockitoBean WorkflowGrpcClient workflowClient;

    @Autowired StatementService service;
    @Autowired PaymentStatementRepository statements;
    @Autowired OutboxRepository outbox;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired EsignEventListener esignListener;

    private final UUID contractId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void stubs() {
        when(contractClient.getContract(any())).thenReturn(GetContractResponse.newBuilder()
                .setId(contractId.toString())
                .setContractNo("CTR-2026-0001")
                .setStatus("ACTIVE")
                .setValidFrom("2026-01-01")
                .setValidTo("2026-12-31")
                .setServiceGroup("STEVEDORING")
                .setVatRate(8.0)
                .setPaymentTerm("NET 30")
                .setCustomerId(customerId.toString())
                .setCustomerName("ACME")
                .setCurrency("VND")
                .build());
        when(operationsClient.listVolumes(any(), any())).thenAnswer(inv -> lockedVolumes(inv.getArgument(1)));
        when(pricingClient.getEffectivePriceList(any(), any(), any(), any()))
                .thenReturn(GetEffectivePriceListResponse.newBuilder()
                        .setPriceListNo("PRC-2026-0001")
                        .setVersionNo(3)
                        .setVersionId(UUID.randomUUID().toString())
                        .addLines(PriceLine.newBuilder()
                                .setServiceCode("CNT").setServiceName("Container handling")
                                .setUnit("TEU").setUnitPrice(100.0).build())
                        .build());
    }

    @Test
    void calculateSnapshotsPay03() {
        StatementResponse response = service.calculate(new CalculateStatementRequest(contractId.toString(), "2026-06"));

        assertThat(response.status()).isEqualTo("CALCULATED");
        assertThat(response.statementNo()).startsWith("PMT-");
        // snapshots, not live references
        assertThat(response.contractNo()).isEqualTo("CTR-2026-0001");
        assertThat(response.customerName()).isEqualTo("ACME");
        assertThat(response.periodStart()).isEqualTo("2026-06-01");
        assertThat(response.periodEnd()).isEqualTo("2026-06-30");
        assertThat(response.priceListNo()).isEqualTo("PRC-2026-0001");
        assertThat(response.priceListVersionNo()).isEqualTo(3);
        assertThat(response.paymentTerm()).isEqualTo("NET 30");
        assertThat(response.vatRate()).isEqualByComparingTo("8.00");
        // 2 volume records grouped into 1 line: 20 x 100 = 2000, VAT 160, total 2160
        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).serviceCode()).isEqualTo("CNT");
        assertThat(response.lines().get(0).quantity()).isEqualByComparingTo("20");
        assertThat(response.lines().get(0).volumeLinks()).hasSize(2);
        assertThat(response.subtotal()).isEqualByComparingTo("2000.00");
        assertThat(response.taxAmount()).isEqualByComparingTo("160.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("2160.00");
        // audit outboxed in the same transaction (D15)
        assertThat(outbox.findAll()).anyMatch(e -> "audit.recorded".equals(e.getEventType()));
    }

    @Test
    void unlockedPeriodRejectedPay02() {
        // doReturn: the @BeforeEach thenAnswer would blow up on null args during a when() re-stub
        doReturn(ListVolumesResponse.newBuilder()
                .setPeriodState("OPEN").setPeriodStart("2026-07-01").setPeriodEnd("2026-07-31").build())
                .when(operationsClient).listVolumes(any(), any());

        assertThatThrownBy(() -> service.calculate(new CalculateStatementRequest(contractId.toString(), "2026-07")))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("LOCKED");
    }

    @Test
    void unpricedServiceIs422Pay01() {
        doReturn(ListVolumesResponse.newBuilder()
                .setPeriodState("LOCKED").setPeriodStart("2026-08-01").setPeriodEnd("2026-08-31")
                .addVolumes(VolumeRecord.newBuilder()
                        .setRecordNo("VOL-9").setServiceCode("UNKNOWN").setUnit("TEU").setQuantity(1).build())
                .build()).when(operationsClient).listVolumes(any(), any());

        assertThatThrownBy(() -> service.calculate(new CalculateStatementRequest(contractId.toString(), "2026-08")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Unpriced service");
    }

    @Test
    void duplicateLiveStatementConflicts() {
        service.calculate(new CalculateStatementRequest(contractId.toString(), "2026-09"));

        assertThatThrownBy(() -> service.calculate(new CalculateStatementRequest(contractId.toString(), "2026-09")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("live statement");
    }

    @Test
    void negativeTotalRejectedPay04() {
        StatementResponse created = service.calculate(new CalculateStatementRequest(contractId.toString(), "2026-10"));
        // driving the total negative is refused by the CHECK constraint — and would have slipped
        // through silently before edits recomputed totals (stale positive total at submit)
        assertThatThrownBy(() -> service.editLine(created.statementNo(),
                new EditLineRequest(1, new BigDecimal("-5.00"), new BigDecimal("10"), null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // the submit-time guard remains as defence in depth for the same rule
        service.reconcile(created.statementNo());
        var ok = service.submit(created.statementNo());
        assertThat(ok.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void fullChainToIssuedThenAdjustmentPay05AndPay07() {
        StatementResponse created = service.calculate(new CalculateStatementRequest(contractId.toString(), "2026-11"));
        String no = created.statementNo();
        service.reconcile(no);
        service.submit(no);
        // submit wrote the D4 dispatch row carrying the idempotency key
        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).anyMatch(e -> "workflow.start_requested".equals(e.getEventType())
                && e.getPayload().contains("idempotency_key"));

        service.updateStatus(no, "APPROVED", "W-instance-1");
        service.sendForSigning(no);
        assertThat(outbox.findAll()).anyMatch(e -> "esign.session_requested".equals(e.getEventType()));

        // PAY-07 via the real listener against real rows
        var stmtId = statements.findByStatementNo(no).orElseThrow().getId();
        UUID eventId = UUID.randomUUID();
        esignListener.onEvent(
                "{\"document_id\":\"" + stmtId + "\",\"result\":\"SIGNED\",\"session_id\":\"" + UUID.randomUUID() + "\"}",
                "esign.session_completed", "PAYMENT_STATEMENT", eventId.toString(), stmtId.toString());

        assertThat(statements.findByStatementNo(no).orElseThrow().getStatus().name()).isEqualTo("SIGNED");
        assertThat(processedEvents.existsById(eventId)).isTrue();
        // redelivery is a no-op (at-least-once)
        esignListener.onEvent(
                "{\"document_id\":\"" + stmtId + "\",\"result\":\"SIGNED\",\"session_id\":\"" + UUID.randomUUID() + "\"}",
                "esign.session_completed", "PAYMENT_STATEMENT", eventId.toString(), stmtId.toString());

        var published = service.publish(no);
        assertThat(published.status()).isEqualTo("ISSUED");
        assertThat(published.dueDate()).isEqualTo(LocalDate.of(2026, 11, 30).plusDays(30));

        var adjustment = service.createAdjustment(no, new AdjustmentRequest("short-count", List.of(
                new AdjustmentRequest.AdjustmentLineInput("CNT", "Container handling", "TEU",
                        new BigDecimal("100.00"), new BigDecimal("1"), null))));
        assertThat(adjustment.status()).isEqualTo("DRAFT");
        assertThat(statements.findByStatementNo(no).orElseThrow().getStatus().name()).isEqualTo("ISSUED");
    }

    private static ListVolumesResponse lockedVolumes(String periodCode) {
        LocalDate start = LocalDate.parse(periodCode + "-01");
        LocalDate end = start.plusMonths(1).minusDays(1);
        return ListVolumesResponse.newBuilder()
                .setPeriodState("LOCKED")
                .setPeriodStart(start.toString())
                .setPeriodEnd(end.toString())
                .addVolumes(VolumeRecord.newBuilder()
                        .setRecordNo("VOL-" + periodCode + "-1").setServiceCode("CNT")
                        .setUnit("TEU").setQuantity(12).setServiceName("Container handling").build())
                .addVolumes(VolumeRecord.newBuilder()
                        .setRecordNo("VOL-" + periodCode + "-2").setServiceCode("CNT")
                        .setUnit("TEU").setQuantity(8).setServiceName("Container handling").build())
                .build();
    }
}
