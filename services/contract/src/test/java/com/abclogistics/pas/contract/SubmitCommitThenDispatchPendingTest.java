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
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.controller.http.ContractController;
import com.abclogistics.pas.contract.dto.SubmitResponse;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import com.abclogistics.pas.workflow.grpc.GetInstanceByDocumentResponse;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.client.WorkflowGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D4 commit-then-dispatch (registry §9 footnote ¹).
 *
 * <p>Submit commits the local status change, its status_history row and a
 * {@code workflow.start_requested} outbox row in ONE transaction, then a relay retries
 * {@code StartInstance}. The reverse order is the bug this pins: a remote success followed by a
 * failed local commit orphans a live, assignee-notified workflow instance on a document still
 * DRAFT, and no retry can undo that.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SubmitCommitThenDispatchPendingTest {

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

    /** SALES_OFFICER's grants, applied only for the fixture upload: the default stays empty. */
    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    /**
     * The workflow service is a separate process (D16). Mocked so these tests pin THIS service's
     * ordering guarantees.
     */
    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractService contracts;
    @Autowired ContractController controller;
    @Autowired CustomerService customers;
    @Autowired AttachmentService attachments;
    @Autowired OutboxRepository outbox;
    @Autowired StatusHistoryRepository history;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitCommitsLocallyBeforeAnyRemoteCall() {
        UUID id = submittableContract();
        long historyBefore = tx.execute(s -> history.count());

        contracts.submit(id);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
        long historyAfter = tx.execute(s -> history.count());
        assertThat(historyAfter).isEqualTo(historyBefore + 1);

        List<OutboxEvent> queued = startRequestsFor(id);
        assertThat(queued).singleElement().satisfies(row -> {
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getClaimedAt()).isNull();
            assertThat(row.getAggregateType()).isEqualTo(EntityType.CONTRACT.name());
        });

        // The dispatch is the relay's job. Nothing remote may have happened inside the submit
        // transaction beyond the read-only pre-check.
        verify(workflow).validateStartable("CONTRACT");
        verify(workflow, never()).startInstance(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void outboxRowCarriesAStableIdempotencyKey() {
        // The key is generated ONCE at submit and reused by every retry, so a lost ack cannot
        // produce a second instance.
        UUID id = submittableContract();
        contracts.submit(id);

        String payload = startRequestsFor(id).get(0).getPayload();
        assertThat(payload).containsPattern("\"idempotencyKey\"\\s*:\\s*\"" + UUID_PATTERN + "\"");

        // and everything StartInstance needs is captured now, so a retry never has to re-read a
        // document that may have moved on since
        assertThat(payload).contains("CONTRACT").contains(id.toString());
        assertThat(payload).contains("ACME Logistics");
        assertThat(payload).contains(SALES.userId().toString()).contains(SALES.fullName());
    }

    @Test
    void pendingDispatchRendersAsInitializationPendingNotAnError() {
        // A document genuinely SUBMITTED with no instance yet is a normal state, not a failure:
        // GetInstanceByDocument NOT_FOUND must render INITIALIZATION_PENDING from local status
        // and must not be retried from the progress endpoint.
        UUID id = submittableContract();
        contracts.submit(id);
        when(workflow.getInstanceByDocument(eq("CONTRACT"), eq(id))).thenReturn(Optional.empty());

        ContractService.ApprovalProgress progress = tx.execute(s -> contracts.progress(id));

        assertThat(progress.state()).isEqualTo("INITIALIZATION_PENDING");
        assertThat(progress.instance()).isNull();
        // read once, not retried: an absent instance is an answer, not a transient failure
        verify(workflow, times(1)).getInstanceByDocument(eq("CONTRACT"), eq(id));
    }

    @Test
    void theRemotePreCheckRunsWithNoTransactionOpen() {
        // The pre-check is a 2s-deadline gRPC call. Running it inside the submit transaction ties
        // connection-pool occupancy to workflow-service latency, so submit brackets it between two
        // transactions instead of wrapping one around it.
        UUID id = submittableContract();
        AtomicBoolean insideTransaction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(workflow).validateStartable("CONTRACT");

        contracts.submit(id);

        assertThat(insideTransaction).isFalse();
        // and the work either side of it still landed
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
        assertThat(startRequestsFor(id)).hasSize(1);
    }

    @Test
    void aCallerCannotPutThePreCheckBackInsideATransaction() {
        // Asserted rather than documented: wrapping submit would silently restore exactly the
        // behaviour the split removes, and the two transactions below would join the caller's.
        UUID id = submittableContract();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> contracts.submit(id)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.DRAFT);
        verify(workflow, never()).validateStartable(any());
    }

    @Test
    void validateStartableFailureBlocksTheCommit() {
        // The pre-check runs BEFORE the commit so an unconfigured document type fails fast (412)
        // instead of parking in the outbox forever.
        UUID id = submittableContract();
        doThrow(new FailedPreconditionException("No active definition for CONTRACT"))
                .when(workflow).validateStartable("CONTRACT");

        assertThatThrownBy(() -> contracts.submit(id))
                .isInstanceOf(FailedPreconditionException.class);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.DRAFT);
        assertThat(startRequestsFor(id)).isEmpty();
    }

    @Test
    void aStaleTerminalInstanceIsDiscardedWhileTheDocumentIsSubmitted() {
        // registry §5: revise and resubmit each mint a new instance, so workflow can still hold a
        // terminal one from the previous round. Rendering it would show the contract's PREVIOUS
        // rejected chain as its current progress for the whole dispatch window.
        UUID id = submittableContract();
        contracts.submit(id);
        when(workflow.getInstanceByDocument(eq("CONTRACT"), eq(id)))
                .thenReturn(Optional.of(GetInstanceByDocumentResponse.newBuilder()
                        .setInstanceId(UUID.randomUUID().toString())
                        .setStatus("REJECTED")
                        .build()));

        ContractService.ApprovalProgress progress = tx.execute(s -> contracts.progress(id));

        assertThat(progress.state()).isEqualTo("INITIALIZATION_PENDING");
        // discarded, not merely relabelled — the caller must not be able to read it back
        assertThat(progress.instance()).isNull();
    }

    @Test
    void aLiveInstanceIsReportedAsItself() {
        UUID id = submittableContract();
        contracts.submit(id);
        when(workflow.getInstanceByDocument(eq("CONTRACT"), eq(id)))
                .thenReturn(Optional.of(GetInstanceByDocumentResponse.newBuilder()
                        .setInstanceId(UUID.randomUUID().toString())
                        .setStatus("IN_PROGRESS")
                        .build()));

        ContractService.ApprovalProgress progress = tx.execute(s -> contracts.progress(id));

        assertThat(progress.state()).isEqualTo("IN_PROGRESS");
        assertThat(progress.instance()).isNotNull();
    }

    @Test
    void theSubmitEndpointReportsBothStatuses() {
        // D14e: the document's status and the workflow's state are two facts, never collapsed
        // into one. The endpoint has to exist -- service-level tests cannot catch a missing route.
        UUID id = submittableContract();
        grant("contract:write");

        SubmitResponse response = controller.submit(id);

        assertThat(response.status()).isEqualTo("SUBMITTED");
        assertThat(response.workflowState()).isEqualTo("INITIALIZATION_PENDING");
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void theSubmitEndpointRequiresTheNamedPermission() {
        // "code checks permissions, never roles" -- SALES is in the principal's roles and buys
        // nothing; contract:write is what the endpoint asks for.
        UUID id = submittableContract();

        assertThatThrownBy(() -> controller.submit(id))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.DRAFT);
    }

    private void grant(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null,
                        java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

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

    /** Granted just for the upload: the tests below assert on a caller holding nothing. */
    private void attach(UUID contractId) {
        MockMultipartFile file = new MockMultipartFile("file", "signed.pdf", "application/pdf",
                "contract terms".getBytes(StandardCharsets.UTF_8));
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, SALES_OFFICER_PERMISSIONS));
        try {
            tx.execute(s -> attachments.upload(EntityType.CONTRACT, contractId, file));
        } finally {
            SecurityContextHolder.getContext().setAuthentication(before);
        }
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
