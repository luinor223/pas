package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.ForbiddenException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.controller.ContractController;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.dto.CancelRequest;
import com.abclogistics.pas.contract.dto.CancelResponse;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.DocumentCancellationService;
import com.abclogistics.pas.contract.service.DocumentCancellationService.Outcome;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient.CancelOutcome;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * §M2 cancel-vs-dispatch handoff.
 *
 * <p>The relay's claim is a timestamp lease with no fencing token, so "claimed a while ago" does
 * not mean "the claimer is dead". Only a row with {@code claimed_at IS NULL} may be cancelled
 * directly; a stale claim is re-claimed and its dispatch forced to completion before
 * {@code CancelInstance} runs.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CancelStaleClaimForcesDispatchTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static final Path STORAGE = createTempStorage();

    private static Path createTempStorage() {
        try {
            return Files.createTempDirectory("pas-cancel").toRealPath();
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
        registry.add("contract.attachment-storage-path", STORAGE::toString);
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractService contracts;
    @Autowired DocumentCancellationService cancellation;
    @Autowired ContractController controller;
    @Autowired CustomerService customers;
    @Autowired AttachmentService attachments;
    @Autowired OutboxRepository outbox;
    @Autowired StatusHistoryRepository history;
    @Autowired JdbcTemplate jdbc;
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

    // --- step 1: the row nobody ever touched ------------------------------------------------

    @Test
    void unclaimedRowIsCancelledAtomically() {
        // claimed_at IS NULL: the outbox row is cancelled in the same transaction as the document,
        // and StartInstance is never called.
        UUID id = submittedContract();

        Outcome outcome = cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew");

        assertThat(outcome).isEqualTo(Outcome.CANCELLED);
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
        assertThat(startRequest(id).getCancelledAt()).isNotNull();
        assertThat(startRequest(id).getPublishedAt()).isNull();
        verify(workflow, never()).cancelInstance(any(), anyString(), any());
        verify(workflow, never()).startInstance(any(), anyString(), any(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    void aDraftIsCancelledWithoutConsultingWorkflowAtAll() {
        // Nothing was ever dispatched for a DRAFT, so there is no race to resolve.
        UUID id = submittableContract();

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "created by mistake")).isEqualTo(Outcome.CANCELLED);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
        verifyNoInteractions(workflow);
    }

    // --- step 2: the claimed row ------------------------------------------------------------

    @Test
    void staleClaimIsForcedToCompletionNotCancelled() {
        // A stale claim must be resolved, never assumed dead: force the dispatch, then cancel the
        // instance it created. Cancelling the row instead would let StartInstance land afterwards.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, STALE);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.NOT_FOUND, CancelOutcome.CANCELLED);
        when(workflow.startInstance(eq(key), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        Outcome outcome = cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew");

        assertThat(outcome).isEqualTo(Outcome.CANCELLED);
        // the dispatch was completed, using the key minted at submit — not a fresh one
        verify(workflow).startInstance(eq(key), eq("CONTRACT"), eq(id), anyString(),
                any(), any(), any(), any());
        // ... and the row records that it happened, so the relay will not repeat it
        OutboxEvent row = startRequest(id);
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(row.getCancelledAt()).isNull();
        // the second CancelInstance is the one that resolved it
        verify(workflow, times(2)).cancelInstance(eq(id), eq("CONTRACT"), eq(key));
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void aClaimedRowIsCancelledThroughWorkflowNotThroughTheOutbox() {
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.CANCELLED);

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew")).isEqualTo(Outcome.CANCELLED);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
        // the row keeps its claim: cancelling it would race the worker that holds it
        assertThat(startRequest(id).getCancelledAt()).isNull();
    }

    @Test
    void documentIsNeverCancelledOnAnInconclusiveRead() {
        // The document stays SUBMITTED/UNDER_REVIEW until one branch definitively resolves, so no
        // workflow instance can start after the document was already CANCELLED.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.NOT_FOUND);

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew")).isEqualTo(Outcome.PENDING);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
        // a fresh claim is not ours to take over
        verify(workflow, never()).startInstance(any(), anyString(), any(), anyString(),
                any(), any(), any(), any());
        assertThat(startRequest(id).getCancelledAt()).isNull();
    }

    @Test
    void aPublishedRowWhoseInstanceIsNotFoundStaysPending() {
        // published_at means the instance exists; NOT_FOUND then means workflow-service has not
        // caught up, which is a reason to wait rather than a reason to dispatch a second time.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, STALE);
        publish(id);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.NOT_FOUND);

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew")).isEqualTo(Outcome.PENDING);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
        verify(workflow, never()).startInstance(any(), anyString(), any(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    void cancelFailsOutrightIfAStepWasAlreadyActioned() {
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.ALREADY_ACTIONED);

        assertThatThrownBy(() -> cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already actioned");

        // Not retried and not pending: an actioned step is a definitive answer.
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
        assertThat(startRequest(id).getCancelledAt()).isNull();
    }

    @Test
    void aFailedForcedDispatchReleasesTheClaimAndStaysPending() {
        // Taking over a stale claim can itself fail. The row must go back to being retryable
        // rather than sitting claimed by a takeover that never finished.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, STALE);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.NOT_FOUND);
        when(workflow.startInstance(eq(key), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew")).isEqualTo(Outcome.PENDING);

        OutboxEvent row = startRequest(id);
        assertThat(row.getClaimedAt()).isNull();
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void aTransportFailureIsNotReadAsAnAnswer() {
        // UNAVAILABLE is not "no instance" and not "already actioned" -- it means the question was
        // never asked, so it propagates instead of resolving the cancel either way.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew"))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void aDocumentThatEntersReviewMidHandoffStillLandsOnCancelled() {
        // The handoff spans a gRPC round trip, so instance_started can flip SUBMITTED ->
        // UNDER_REVIEW while CancelInstance is in flight. Once workflow has accepted the cancel
        // there is no taking it back, so the local edge has to exist -- otherwise the instance is
        // cancelled and the contract is stranded UNDER_REVIEW.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key))).thenAnswer(invocation -> {
            force(id, DocumentStatus.UNDER_REVIEW);
            return CancelOutcome.CANCELLED;
        });

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew")).isEqualTo(Outcome.CANCELLED);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
        assertThat(edgeInto(id, DocumentStatus.CANCELLED)).isEqualTo(DocumentStatus.UNDER_REVIEW);
    }

    @Test
    void cancellingAnUnderReviewContractGoesThroughWorkflow() {
        // An UNDER_REVIEW document has a live instance by definition. Cancelling it locally
        // without telling workflow-service would leave that instance running with assignees on it.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        force(id, DocumentStatus.UNDER_REVIEW);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.CANCELLED);

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew")).isEqualTo(Outcome.CANCELLED);

        verify(workflow).cancelInstance(eq(id), eq("CONTRACT"), eq(key));
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void anUnderReviewCancelStillFailsOutrightOnAnActionedStep() {
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        force(id, DocumentStatus.UNDER_REVIEW);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.ALREADY_ACTIONED);

        assertThatThrownBy(() -> cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew"))
                .isInstanceOf(ConflictException.class);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.UNDER_REVIEW);
    }

    @Test
    void anUnderReviewContractIsNeverCancelledWithoutTheRoundTrip() {
        // The UNDER_REVIEW -> CANCELLED edge is restricted to the M2 handoff. With no dispatch
        // intent there is no idempotency_key to cancel against, so the edge must not be applied
        // on trust -- doing so would strand a live instance with assignees on it.
        UUID id = submittedContract();
        dropStartRequest(id);
        force(id, DocumentStatus.UNDER_REVIEW);

        assertThatThrownBy(() -> cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no dispatch intent");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.UNDER_REVIEW);
        verify(workflow, never()).cancelInstance(any(), anyString(), any());
    }

    @Test
    void anUnderReviewCancelStaysPendingWhileTheInstanceIsUnconfirmed() {
        // NOT_FOUND is inconclusive, and inconclusive must not reach the restricted edge either.
        UUID id = submittedContract();
        UUID key = idempotencyKeyOf(id);
        claimedAgo(id, FRESH);
        force(id, DocumentStatus.UNDER_REVIEW);
        when(workflow.cancelInstance(eq(id), eq("CONTRACT"), eq(key)))
                .thenReturn(CancelOutcome.NOT_FOUND);

        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew")).isEqualTo(Outcome.PENDING);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.UNDER_REVIEW);
    }

    // --- status guards and audit ------------------------------------------------------------

    @Test
    void anAlreadyCancelledContractCannotBeCancelledAgain() {
        UUID id = submittedContract();
        cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew");

        assertThatThrownBy(() -> cancellation.cancel(EntityType.CONTRACT, id, "again"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void cancellingAnActiveContractNeedsItsOwnPermission() {
        // CTR-06: contract:write cancels a DRAFT; a live contract is a different decision.
        UUID id = submittableContract();
        force(id, DocumentStatus.ACTIVE);

        assertThatThrownBy(() -> cancellation.cancel(EntityType.CONTRACT, id, "terminated early"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("contract:cancel_active");
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);

        grant("contract:write", "contract:cancel_active");
        assertThat(cancellation.cancel(EntityType.CONTRACT, id, "terminated early")).isEqualTo(Outcome.CANCELLED);
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void everyCancellationWritesExactlyOneStatusHistoryRow() {
        UUID id = submittedContract();
        long before = historyRows();

        cancellation.cancel(EntityType.CONTRACT, id, "customer withdrew");

        assertThat(historyRows()).isEqualTo(before + 1);
    }

    // --- the endpoint -----------------------------------------------------------------------

    @Test
    void theEndpointDistinguishesCancelledFromPending() {
        grant("contract:write");
        UUID cancelled = submittedContract();
        UUID pending = submittedContract();
        claimedAgo(pending, FRESH);
        when(workflow.cancelInstance(eq(pending), eq("CONTRACT"), eq(idempotencyKeyOf(pending))))
                .thenReturn(CancelOutcome.NOT_FOUND);

        ResponseEntity<CancelResponse> ok = controller.cancel(cancelled, new CancelRequest("withdrew"));
        ResponseEntity<CancelResponse> accepted = controller.cancel(pending, new CancelRequest("withdrew"));

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().status()).isEqualTo("CANCELLED");
        // 202 has to say why, or the client cannot tell it apart from a slow success
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().status()).isEqualTo("PENDING");
        assertThat(accepted.getBody().detail()).contains("Retry");
    }

    @Test
    void theEndpointAcceptsACancelWithNoBody() {
        grant("contract:write");
        UUID id = submittedContract();

        assertThat(controller.cancel(id, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.CANCELLED);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Older than the default 60s claim lease, so the relay itself would reclaim it. */
    private static final long STALE = 600;
    /** Claimed just now — the worker holding it is plainly alive. */
    private static final long FRESH = 0;

    /** Rewrites {@code claimed_at} directly: a relay worker's claim is not reachable from the API. */
    private void claimedAgo(UUID contractId, long secondsAgo) {
        Instant claimedAt = Instant.now().minus(secondsAgo, ChronoUnit.SECONDS);
        jdbc.update("update contract.outbox set claimed_at = ? where id = ?",
                Timestamp.from(claimedAt), startRequest(contractId).getId());
    }

    private void publish(UUID contractId) {
        jdbc.update("update contract.outbox set published_at = ? where id = ?",
                Timestamp.from(Instant.now()), startRequest(contractId).getId());
    }

    /** The status the document was in when it reached {@code to}, per its status_history. */
    private DocumentStatus edgeInto(UUID contractId, DocumentStatus to) {
        List<StatusHistory> rows = tx.execute(s ->
                history.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(EntityType.CONTRACT, contractId));
        return rows.stream()
                .filter(row -> row.getToStatus() == to)
                .map(StatusHistory::getFromStatus)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no transition into " + to));
    }

    /** Simulates a document under review whose dispatch intent is not recoverable. */
    private void dropStartRequest(UUID contractId) {
        jdbc.update("delete from contract.outbox where id = ?", startRequest(contractId).getId());
    }

    private void force(UUID contractId, DocumentStatus status) {
        tx.executeWithoutResult(s -> contracts.get(contractId).setStatus(status));
    }

    private void grant(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null,
                        Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    private UUID submittedContract() {
        UUID id = submittableContract();
        tx.executeWithoutResult(s -> contracts.submit(id));
        return id;
    }

    private UUID submittableContract() {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "initial", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
        MockMultipartFile file = new MockMultipartFile("file", "signed.pdf", "application/pdf",
                "contract terms".getBytes(StandardCharsets.UTF_8));
        tx.execute(s -> attachments.upload(EntityType.CONTRACT, id, file));
        return id;
    }

    /** Bound to a typed local: {@code assertThat} has an overload for almost everything. */
    private long historyRows() {
        Long count = tx.execute(s -> history.count());
        return count == null ? 0 : count;
    }

    private DocumentStatus statusOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getStatus());
    }

    private OutboxEvent startRequest(UUID contractId) {
        return tx.execute(s -> outbox.findAll().stream()
                .filter(e -> contractId.equals(e.getAggregateId()))
                .filter(e -> "workflow.start_requested".equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no start_requested row for " + contractId)));
    }

    private UUID idempotencyKeyOf(UUID contractId) {
        String payload = startRequest(contractId).getPayload();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"idempotencyKey\"\\s*:\\s*\"([0-9a-f-]{36})\"")
                .matcher(payload);
        assertThat(m.find()).as("payload carries an idempotency key: %s", payload).isTrue();
        return UUID.fromString(m.group(1));
    }
}
