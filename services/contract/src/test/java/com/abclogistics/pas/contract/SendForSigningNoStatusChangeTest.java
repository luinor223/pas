package com.abclogistics.pas.contract;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.controller.http.ContractController;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerContactRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.scheduler.ContractStatusScheduler;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.client.WorkflowGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D10 / D14e — contract-service owns the send-for-signing action (registry §6 third outbox use),
 * and it changes NO document status. Requirement 5.5 forbids mixing approval state and signing
 * state; the frontend composes the two for display.
 *
 * <p>The outbox is required rather than a synchronous call: APR-07 wants the user's send action to
 * survive esign-service being down, and a synchronous call committed afterwards would leave a
 * session sending to the provider for a document still APPROVED, then deliver a callback §9 has
 * no transition for.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SendForSigningNoStatusChangeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

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
        registry.add("contract.kafka.listener-enabled", () -> "false");
        // the D14d sweep runs on a schedule; these tests drive their own dates and statuses
        registry.add("contract.status-sweep-enabled", () -> "false");
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    /** SALES_OFFICER carries esign:send (registry §7); these tests bypass the controller. */
    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write", "esign:send")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired ContractStatusScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ContractController controller;

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
    void sendWritesAnOutboxRowAndLeavesStatusUntouched() {
        // Status stays APPROVED and NO status_history row is written -- this is not a transition.
        UUID id = approvedContract();
        int historyBefore = historyRows(id);

        contracts.sendForSigning(id);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(historyRows(id)).isEqualTo(historyBefore);
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(1);
        // the key travels with the row so every retry reaches the same session (§M2)
        assertThat(payloadOf(id, "esign.session_requested"))
                .contains("idempotencyKey").contains("b@acme.vn").contains("Tran Thi B");
    }

    @Test
    void sendIsAuditedEvenThoughNothingMoved() {
        // "who sent this for signature, and when" is exactly what the History tab is for (D15),
        // and no status_history row will ever carry it.
        UUID id = approvedContract();

        contracts.sendForSigning(id);

        assertThat(payloadOf(id, "SEND_FOR_SIGNING")).contains("b@acme.vn");
    }

    @Test
    void sendIsRejectedUnlessApproved() {
        // GetSigningPayload's guard is status = APPROVED (registry §5).
        UUID id = approvedContract();
        setStatus(id, DocumentStatus.DRAFT);

        assertThatThrownBy(() -> contracts.sendForSigning(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("APPROVED");

        assertThat(outboxRows(id, "esign.session_requested")).isZero();
    }

    @Test
    void sendIsRefusedWhenThereIsNobodyToAddressItTo() {
        // Better than dispatching a row the relay will retry for ever against a blank address.
        UUID id = approvedContractWithoutPrimaryContact();

        assertThatThrownBy(() -> contracts.sendForSigning(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("primary contact");

        assertThat(outboxRows(id, "esign.session_requested")).isZero();
    }

    @Test
    void sendRequiresEsignSendPermission() {
        // contract:write is not enough: sending a document to a customer is its own grant (§7).
        UUID id = approvedContract();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null,
                        List.of(new SimpleGrantedAuthority("contract:write"))));

        assertThatThrownBy(() -> controller.sendForSigning(id))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void activationStillFiresWhileSigningIsPending() {
        // APPROVED -> ACTIVE is purely date-driven and must not wait on a signing session.
        UUID id = approvedContract();
        contracts.sendForSigning(id);

        scheduler.sweep();

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);
        // and the send is still queued: nothing about it was cancelled or rewritten by the flip
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(1);
    }

    // --- helpers --------------------------------------------------------------------------------

    private UUID approvedContract() {
        return approved(new CustomerRequest("ACME Logistics", null, null, null, null, null, null,
                List.of(new CustomerContactRequest("Tran Thi B", "Director",
                        "b@acme.vn", "0900000000", true))));
    }

    private UUID approvedContractWithoutPrimaryContact() {
        return approved(new CustomerRequest("Beta Trading", null, null, null, null, null, null,
                List.of()));
    }

    private UUID approved(CustomerRequest customer) {
        UUID customerId = tx.execute(s -> customers.create(customer).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "for signature", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.now().minusDays(1), LocalDate.now().plusYears(1),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
        setStatus(id, DocumentStatus.APPROVED);
        return id;
    }

    private void setStatus(UUID id, DocumentStatus status) {
        tx.executeWithoutResult(s -> contracts.get(id).setStatus(status));
    }

    private DocumentStatus statusOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getStatus());
    }

    private int historyRows(UUID id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from contract.status_history where entity_id = ?",
                Integer.class, id);
        return count == null ? 0 : count;
    }

    private int outboxRows(UUID id, String marker) {
        Integer count = jdbc.queryForObject(
                "select count(*) from contract.outbox where aggregate_id = ? and "
                        + "(event_type = ? or payload::text like ?)",
                Integer.class, id, marker, "%" + marker + "%");
        return count == null ? 0 : count;
    }

    private String payloadOf(UUID id, String marker) {
        return jdbc.queryForObject(
                "select payload::text from contract.outbox where aggregate_id = ? and "
                        + "(event_type = ? or payload::text like ?) limit 1",
                String.class, id, marker, "%" + marker + "%");
    }
}
