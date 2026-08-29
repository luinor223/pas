package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.error.FailedPreconditionException;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CTR-02 — submit requires a valid customer, a valid validity window and at least one attachment.
 * Session 3 additionally requires vatRate and paymentTerm, which billing snapshots for PAY-03:
 * a null VAT silently treated as 0% is exactly the invoice drift the system exists to prevent.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CTR02SubmitRequiresAttachmentTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static final Path STORAGE = createTempStorage();

    private static Path createTempStorage() {
        try {
            return Files.createTempDirectory("pas-submit").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("outbox.relay.enabled", () -> "false");
        registry.add("contract.kafka.listener-enabled", () -> "false");
        // the D14d sweep runs on a schedule; these tests drive their own dates and statuses
        registry.add("contract.status-sweep-enabled", () -> "false");
        registry.add("contract.attachment-storage-path", STORAGE::toString);
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    /** SALES_OFFICER's grants: AttachmentService checks them, and these tests bypass the controller. */
    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    /**
     * The workflow service is a separate process (D16). Mocked so these tests pin THIS service's
     * ordering guarantees; the wire contract is covered by ContractContractTest against the proto.
     */
    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired AttachmentService attachments;
    @Autowired OutboxRepository outbox;
    @Autowired StatusHistoryRepository history;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, SALES_OFFICER_PERMISSIONS));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitWithoutAttachmentIsRejected() {
        UUID id = contractWith(new BigDecimal("10"), "NET30");

        assertThatThrownBy(() -> contracts.submit(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("CTR-02")
                .hasMessageContaining("attachment");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.DRAFT);
        assertThat(startRequestsFor(id)).isEmpty();
    }

    @Test
    void submitWithSuspendedCustomerIsRejected() {
        UUID id = submittableContract();
        UUID customerId = tx.execute(s -> contracts.get(id).getCustomer().getId());
        // The customer was ACTIVE when the draft was written; CTR-02 re-reads it at submit
        // because that is the moment the obligation becomes real.
        tx.executeWithoutResult(s -> customers.suspend(customerId, "unpaid invoices"));

        assertThatThrownBy(() -> contracts.submit(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("CTR-02")
                .hasMessageContaining("SUSPENDED");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.DRAFT);
    }

    @Test
    void submitWithoutVatRateOrPaymentTermIsRejected() {
        // Both are nullable in DRAFT and required at submit. A null vatRate must be refused,
        // never defaulted to zero.
        UUID noVat = contractWith(null, "NET30");
        attach(noVat);
        assertThatThrownBy(() -> contracts.submit(noVat))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("vatRate")
                .hasMessageContaining("never assumed to be 0");

        UUID noTerm = contractWith(new BigDecimal("10"), null);
        attach(noTerm);
        assertThatThrownBy(() -> contracts.submit(noTerm))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("paymentTerm");
    }

    @Test
    void vatRateOutsideZeroToHundredIsRejected() {
        // numeric(5,2) only bounds it to +/-999.99; the business range is a submit check.
        UUID id = contractWith(new BigDecimal("150"), "NET30");
        attach(id);

        assertThatThrownBy(() -> contracts.submit(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("between 0 and 100");
    }

    @Test
    void zeroVatRateIsAccepted() {
        // 0% is a deliberate, billable value and must not be conflated with "not stated".
        UUID id = contractWith(BigDecimal.ZERO, "NET30");
        attach(id);

        contracts.submit(id);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void onlyADraftCanBeSubmitted() {
        UUID id = submittableContract();
        contracts.submit(id);

        assertThatThrownBy(() -> contracts.submit(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("SUBMITTED");
        // and the second attempt did not queue a second dispatch
        assertThat(startRequestsFor(id)).hasSize(1);
    }

    // ---- helpers -------------------------------------------------------------------------

    /** A contract that satisfies every CTR-02 prerequisite. */
    private UUID submittableContract() {
        UUID id = contractWith(new BigDecimal("10"), "NET30");
        attach(id);
        return id;
    }

    private UUID contractWith(BigDecimal vatRate, String paymentTerm) {
        UUID customerId = newCustomer();
        return tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "initial", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                paymentTerm, "MONTHLY", vatRate, null, null, null)).getId());
    }

    private UUID newCustomer() {
        return tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
    }

    private void attach(UUID contractId) {
        MockMultipartFile file = new MockMultipartFile("file", "signed.pdf", "application/pdf",
                "contract terms".getBytes(StandardCharsets.UTF_8));
        tx.execute(s -> attachments.upload(EntityType.CONTRACT, contractId, file));
    }

    private DocumentStatus statusOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getStatus());
    }

    private List<OutboxEvent> startRequestsFor(UUID contractId) {
        return tx.execute(s -> outbox.findAll().stream()
                .filter(e -> contractId.equals(e.getAggregateId()))
                .filter(e -> "workflow.start_requested".equals(e.getEventType()))
                .toList());
    }
}
