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
import com.abclogistics.pas.common.error.GlobalExceptionHandler;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.controller.http.ContractController;
import com.abclogistics.pas.contract.controller.http.AddendumController;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerContactRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.scheduler.ContractStatusScheduler;
import com.abclogistics.pas.contract.listener.WorkflowEventListener;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.SigningRequestService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired AddendumService addenda;
    @Autowired AddendumController addendumController;
    @Autowired WorkflowEventListener eventListener;
    @Autowired SigningRequestService signingRequests;
    private MockMvc mvc;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, SALES_OFFICER_PERMISSIONS));
        mvc = MockMvcBuilders.standaloneSetup(controller, addendumController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
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
    void approvedAddendumSendWritesOneIntentWithoutChangingApprovalStatus() {
        UUID id = approvedAddendum(approvedContract());
        int historyBefore = historyRows(id);

        var queued = addenda.sendForSigning(id);

        assertThat(addendumStatusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(historyRows(id)).isEqualTo(historyBefore);
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(1);
        assertThat(payloadOf(id, "esign.session_requested"))
                .contains("ADDENDUM").contains("b@acme.vn").contains("Tran Thi B");
        assertThat(queued.requestQueued()).isTrue();
        assertThat(queued.canSendForSigning()).isFalse();
        assertThat(queued.sessionId()).isNull();
        assertThat(addenda.signingRequestState(id)).isEqualTo(queued);
        assertThat(addendumController.signingRequestState(id).requestQueued()).isTrue();
    }

    @Test
    void addendumSendRejectsEveryNonApprovedStatus() {
        UUID id = approvedAddendum(approvedContract());
        for (DocumentStatus status : DocumentStatus.values()) {
            if (status == DocumentStatus.APPROVED) continue;
            setAddendumStatus(id, status);
            assertThatThrownBy(() -> addenda.sendForSigning(id))
                    .as("status %s", status)
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("This document must be approved before it can be sent for signature.");
        }
        assertThat(outboxRows(id, "esign.session_requested")).isZero();
    }

    @Test
    void nonApprovedSendReturnsABusinessFriendlyApiError() throws Exception {
        UUID id = approvedAddendum(approvedContract());
        setAddendumStatus(id, DocumentStatus.DRAFT);

        String body = mvc.perform(post("/addenda/{id}/send-for-signing", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("This document must be approved before it can be sent for signature."))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("ADDENDUM", "DRAFT", "APPROVED", "D10");
    }

    @Test
    void addendumSendRejectsMissingSignerEmail() {
        UUID contractId = approvedContract();
        jdbc.update("update contract.customer_contact set email = null where customer_id = "
                + "(select customer_id from contract.contract where id = ?)", contractId);
        UUID id = approvedAddendum(contractId);

        assertThatThrownBy(() -> addenda.sendForSigning(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("email address");
        assertThat(outboxRows(id, "esign.session_requested")).isZero();
    }

    @Test
    void addendumSendRejectsBlankSignerNameAndMalformedEmail() {
        UUID contractId = approvedContract();
        UUID id = approvedAddendum(contractId);
        jdbc.update("update contract.customer_contact set full_name = ' ' where customer_id = "
                + "(select customer_id from contract.contract where id = ?)", contractId);

        assertThatThrownBy(() -> addenda.sendForSigning(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("signer name");

        jdbc.update("update contract.customer_contact set full_name = 'Signer', email = 'invalid' "
                + "where customer_id = (select customer_id from contract.contract where id = ?)",
                contractId);
        assertThatThrownBy(() -> addenda.sendForSigning(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("valid email address");
        assertThat(outboxRows(id, "esign.session_requested")).isZero();
    }

    @Test
    void addendumSendRequiresEsignPermission() {
        UUID id = approvedAddendum(approvedContract());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null,
                        List.of(new SimpleGrantedAuthority("addendum:write"))));

        assertThatThrownBy(() -> addendumController.sendForSigning(id))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(outboxRows(id, "esign.session_requested")).isZero();
    }

    @Test
    void signingRequestStateUsesDocumentReadOrEsignPermission() {
        UUID contractId = approvedContract();
        UUID addendumId = approvedAddendum(contractId);

        authWith("contract:read");
        assertThat(controller.signingRequestState(contractId).canSendForSigning()).isFalse();
        assertThatThrownBy(() -> addendumController.signingRequestState(addendumId))
                .isInstanceOf(AccessDeniedException.class);

        authWith("addendum:read");
        assertThat(addendumController.signingRequestState(addendumId).canSendForSigning()).isFalse();
        assertThatThrownBy(() -> controller.signingRequestState(contractId))
                .isInstanceOf(AccessDeniedException.class);

        authWith("esign:send");
        assertThat(controller.signingRequestState(contractId).canSendForSigning()).isTrue();
        assertThat(addendumController.signingRequestState(addendumId).canSendForSigning()).isTrue();

        authWith("customer:read");
        assertThatThrownBy(() -> controller.signingRequestState(contractId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> addendumController.signingRequestState(addendumId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void duplicateAndConcurrentAddendumSendsQueueOneIntent() throws Exception {
        UUID sequentialId = approvedAddendum(approvedContract());
        addenda.sendForSigning(sequentialId);
        addenda.sendForSigning(sequentialId);
        assertThat(outboxRows(sequentialId, "esign.session_requested")).isEqualTo(1);

        UUID concurrentId = approvedAddendum(approvedContract());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = pool.submit(() -> concurrentSend(concurrentId, start));
            Future<Throwable> second = pool.submit(() -> concurrentSend(concurrentId, start));
            assertThat(first.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(outboxRows(concurrentId, "esign.session_requested")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void publishedIntentRemainsIdempotentUntilItsSigningSessionCompletes() {
        UUID id = approvedAddendum(approvedContract());
        addenda.sendForSigning(id);
        UUID requestKey = signingRequestKey(id);
        jdbc.update("update contract.outbox set published_at = now() where aggregate_id = ? "
                + "and event_type = 'esign.session_requested'", id);

        addenda.sendForSigning(id);
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(1);

        eventListener.onSigningCompleted(
                "{\"idempotency_key\":\"%s\",\"session_id\":\"%s\"}"
                        .formatted(requestKey, UUID.randomUUID()),
                "ADDENDUM", UUID.randomUUID().toString(), id);
        addenda.sendForSigning(id);
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(2);

        eventListener.onSigningCompleted(
                "{\"idempotency_key\":\"%s\",\"session_id\":\"%s\"}"
                        .formatted(requestKey, UUID.randomUUID()),
                "ADDENDUM", UUID.randomUUID().toString(), id);
        addenda.sendForSigning(id);
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(2);
    }

    @Test
    void oldCompletionPayloadCorrelatesBySessionIdDuringRollingDeployment() {
        UUID id = approvedAddendum(approvedContract());
        addenda.sendForSigning(id);
        UUID requestKey = signingRequestKey(id);
        UUID sessionId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String oldPayload = "{\"session_id\":\"%s\"}".formatted(sessionId);

        assertThatThrownBy(() -> eventListener.onSigningCompleted(
                oldPayload, "ADDENDUM", eventId, id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been associated");

        signingRequests.associateSession("ADDENDUM", id, requestKey, sessionId);
        eventListener.onSigningCompleted(oldPayload, "ADDENDUM", eventId, id);
        addenda.sendForSigning(id);
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(2);
        signingRequests.associateSession(
                "ADDENDUM", id, signingRequestKey(id), UUID.randomUUID());

        eventListener.onSigningCompleted(oldPayload, "ADDENDUM", UUID.randomUUID().toString(), id);
        addenda.sendForSigning(id);
        assertThat(outboxRows(id, "esign.session_requested")).isEqualTo(2);
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
                .hasMessage("This document must be approved before it can be sent for signature.");

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

    private UUID approvedAddendum(UUID contractId) {
        UUID id = tx.execute(s -> addenda.create(new AddendumRequest(
                contractId, "TERM_EXTENSION", "renewal for signature", LocalDate.now(),
                LocalDate.now().plusYears(2), null, null, null)).getId());
        setAddendumStatus(id, DocumentStatus.APPROVED);
        return id;
    }

    private Throwable concurrentSend(UUID id, CyclicBarrier start) {
        authenticate();
        try {
            start.await(10, TimeUnit.SECONDS);
            addenda.sendForSigning(id);
            return null;
        } catch (Throwable failure) {
            return failure;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authWith(String permission) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null,
                        List.of(new SimpleGrantedAuthority(permission))));
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

    private void setAddendumStatus(UUID id, DocumentStatus status) {
        tx.executeWithoutResult(s -> addenda.get(id).setStatus(status));
    }

    private DocumentStatus addendumStatusOf(UUID id) {
        return tx.execute(s -> addenda.get(id).getStatus());
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

    private UUID signingRequestKey(UUID id) {
        return jdbc.queryForObject(
                "select (payload::jsonb ->> 'idempotencyKey')::uuid from contract.outbox "
                        + "where aggregate_id = ? and event_type = 'esign.session_requested' "
                        + "order by created_at desc limit 1",
                UUID.class, id);
    }
}
