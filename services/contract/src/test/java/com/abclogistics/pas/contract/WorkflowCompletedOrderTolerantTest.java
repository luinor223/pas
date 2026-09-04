package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.listener.WorkflowEventListener;
import com.abclogistics.pas.contract.repository.ProcessedEventRepository;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import com.abclogistics.pas.contract.service.AddendumService;
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
 * registry §9 footnote ¹ — order tolerance.
 *
 * <p>Kafka orders sends, not commits, so {@code workflow.completed} can arrive while the document
 * is still SUBMITTED (its {@code instance_started} delayed in the outbox past the whole approval).
 * The skipped SUBMITTED → UNDER_REVIEW edge is applied first, then the outcome, in one
 * transaction, one status_history row each. Rejecting the out-of-order event would wedge the
 * document permanently.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class WorkflowCompletedOrderTolerantTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static final Path STORAGE = createTempStorage();

    private static Path createTempStorage() {
        try {
            return Files.createTempDirectory("pas-consume").toRealPath();
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

    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired WorkflowEventListener listener;
    @Autowired AddendumService addenda;
    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired AttachmentService attachments;
    @Autowired StatusHistoryRepository history;
    @Autowired ProcessedEventRepository processed;
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
    void completedArrivingWhileSubmittedAppliesBothEdges() {
        // Final status APPROVED, and TWO history rows: SUBMITTED->UNDER_REVIEW then
        // UNDER_REVIEW->APPROVED. Collapsing them into one row loses the audit trail D17 exists for.
        UUID id = submittedContract();
        UUID instanceId = UUID.randomUUID();

        completed(id, instanceId, "APPROVED", UUID.randomUUID());

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(edgesAfterSubmit(id)).containsExactly(
                "SUBMITTED->UNDER_REVIEW", "UNDER_REVIEW->APPROVED");
        // both rows attribute the change to the workflow, and to the instance that made it
        assertThat(rows(id)).allSatisfy(row -> {
            if (row.getFromStatus() != DocumentStatus.DRAFT) {
                assertThat(row.getTriggerKind()).isEqualTo(TriggerKind.W);
                assertThat(row.getTriggerRef()).isEqualTo(instanceId);
            }
        });
    }

    @Test
    void theOrdinaryOrderAppliesOneEdgeEach() {
        UUID id = submittedContract();
        UUID instanceId = UUID.randomUUID();

        started(id, instanceId, UUID.randomUUID());
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.UNDER_REVIEW);

        completed(id, instanceId, "REJECTED", UUID.randomUUID());

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.REJECTED);
        assertThat(edgesAfterSubmit(id)).containsExactly(
                "SUBMITTED->UNDER_REVIEW", "UNDER_REVIEW->REJECTED");
    }

    @Test
    void bothEventsAreIdempotentInEitherOrder() {
        // instance_started arriving late, after completed already applied its edge, must be a
        // no-op via processed_event -- not a second UNDER_REVIEW row and not an error.
        UUID id = submittedContract();
        UUID instanceId = UUID.randomUUID();
        completed(id, instanceId, "REVISION_REQUESTED", UUID.randomUUID());

        started(id, instanceId, UUID.randomUUID());

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.REVISION_REQUESTED);
        assertThat(edgesAfterSubmit(id)).containsExactly(
                "SUBMITTED->UNDER_REVIEW", "UNDER_REVIEW->REVISION_REQUESTED");
    }

    @Test
    void redeliveryAfterOffsetLossIsANoOp() {
        // Offsets commit after processing, so a mid-batch death re-reads applied records.
        UUID id = submittedContract();
        UUID instanceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        completed(id, instanceId, "APPROVED", eventId);
        long rowsAfterFirst = historyRows();

        completed(id, instanceId, "APPROVED", eventId);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(historyRows()).isEqualTo(rowsAfterFirst);
    }

    @Test
    void aRedeliveryWithAFreshEventIdIsStillNotAppliedTwice() {
        // Dedup is the first line, not the only one: a genuinely new event id carrying an outcome
        // the document already has must still leave the timeline alone.
        UUID id = submittedContract();
        UUID instanceId = UUID.randomUUID();
        completed(id, instanceId, "APPROVED", UUID.randomUUID());
        long rowsAfterFirst = historyRows();

        completed(id, instanceId, "APPROVED", UUID.randomUUID());

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(historyRows()).isEqualTo(rowsAfterFirst);
    }

    @Test
    void everyAppliedEventLeavesADedupRow() {
        UUID id = submittedContract();
        UUID eventId = UUID.randomUUID();

        started(id, UUID.randomUUID(), eventId);

        assertThat(dedupped(eventId)).isTrue();
    }

    @Test
    void anEventForAnotherServicesDocumentIsIgnoredEntirely() {
        // pas.events carries every service's traffic; a PRICE_LIST record must not even be parsed.
        UUID id = submittedContract();
        UUID eventId = UUID.randomUUID();

        tx.executeWithoutResult(s -> listener.onCompleted(
                completedPayload(UUID.randomUUID(), "APPROVED"), "PRICE_LIST", eventId.toString(), id));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
        // and it leaves no dedup row either: it was never ours to process
        assertThat(dedupped(eventId)).isFalse();
    }

    @Test
    void anEventForAnUnknownContractIsDeduppedRatherThanRetried() {
        // Nothing to apply and nothing a retry would fix, so the dedup row stops it coming back
        // round the partition forever.
        UUID unknown = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        tx.executeWithoutResult(s -> listener.onCompleted(
                completedPayload(UUID.randomUUID(), "APPROVED"), "CONTRACT", eventId.toString(), unknown));

        assertThat(dedupped(eventId)).isTrue();
    }

    @Test
    void anOutcomeThisServiceHasNoStatusForIsRejectedNotGuessed() {
        // Better a dead-lettered record than a document silently left in the wrong state.
        UUID id = submittedContract();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> listener.onCompleted(
                completedPayload(UUID.randomUUID(), "ESCALATED"), "CONTRACT",
                UUID.randomUUID().toString(), id)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESCALATED");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void aDirectPublishedEventWithNoEventIdIsIgnoredNotDeadLettered() {
        // pas.events also carries the three direct-publish events (D9), which have no outbox row
        // and therefore no event_id. Demanding the header before deciding the record is even ours
        // would dead-letter every one of them.
        UUID id = submittedContract();

        tx.executeWithoutResult(s -> listener.onEvent(
                "{\"document_id\":\"%s\",\"days_remaining\":30}".formatted(id),
                "document.expiring", "CONTRACT", null, id.toString()));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void anotherServicesEventWithNoEventIdIsAlsoIgnored() {
        UUID id = submittedContract();

        tx.executeWithoutResult(s -> listener.onEvent(
                "{}", "operations.period_locked", null, null, "2026-01"));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void aCompletedForAnotherOwnersDocumentIsIgnoredBeforeItsHeadersAreDemanded() {
        // All three owner services consume workflow.completed; only one owns a given document.
        UUID id = submittedContract();

        tx.executeWithoutResult(s -> listener.onEvent(
                completedPayload(UUID.randomUUID(), "APPROVED"),
                "workflow.completed", "PRICE_LIST", null, UUID.randomUUID().toString()));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void oneOfOursWithNoEventIdIsRejectedSoItCannotBeAppliedTwice() {
        // Now it IS ours, and dedup is impossible without the header -- that one goes to the DLT.
        UUID id = submittedContract();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> listener.onEvent(
                completedPayload(UUID.randomUUID(), "APPROVED"),
                "workflow.completed", "CONTRACT", null, id.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event_id");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void oneOfOursWithAKeyThatIsNotADocumentIdIsRejected() {
        UUID id = submittedContract();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> listener.onEvent(
                completedPayload(UUID.randomUUID(), "APPROVED"), "workflow.completed",
                "CONTRACT", UUID.randomUUID().toString(), "not-a-uuid")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a document id");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    @Test
    void theFullEnvelopeRouteAppliesTheSameTransition() {
        // onEvent is what Kafka actually calls; the handlers being right is not enough.
        UUID id = submittedContract();
        UUID instanceId = UUID.randomUUID();

        tx.executeWithoutResult(s -> listener.onEvent(
                completedPayload(instanceId, "APPROVED"), "workflow.completed", "CONTRACT",
                UUID.randomUUID().toString(), id.toString()));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(edgesAfterSubmit(id)).containsExactly(
                "SUBMITTED->UNDER_REVIEW", "UNDER_REVIEW->APPROVED");
    }

    // --- addenda: the same machine, under their own document type ---------------------------

    @Test
    void anAddendumReachesUnderReviewOnItsOwnInstanceStarted() {
        // An addendum starts its own workflow instance (4.3). A consumer that only handled
        // CONTRACT would leave it at SUBMITTED for ever -- never reviewed, never approved, and
        // therefore never activated to apply its effects to the parent.
        UUID id = submittedAddendum();

        tx.executeWithoutResult(s -> listener.onEvent(
                startedPayload(UUID.randomUUID(), id), "workflow.instance_started", "ADDENDUM",
                UUID.randomUUID().toString(), id.toString()));

        assertThat(addendumStatus(id)).isEqualTo(DocumentStatus.UNDER_REVIEW);
    }

    @Test
    void anAddendumReachesApprovedAndIsThenActivatable() {
        // The end of the chain the P0 gap cut: approved by workflow, then activated by the D14d
        // sweep, which is what applies TERM_EXTENSION to the parent.
        UUID contractId = activeContract();
        UUID id = submittedAddendum(contractId);
        UUID instanceId = UUID.randomUUID();

        tx.executeWithoutResult(s -> listener.onEvent(
                completedPayload(instanceId, "APPROVED"), "workflow.completed", "ADDENDUM",
                UUID.randomUUID().toString(), id.toString()));

        assertThat(addendumStatus(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(addendumEdges(id)).containsExactly(
                "SUBMITTED->UNDER_REVIEW", "UNDER_REVIEW->APPROVED");

        tx.executeWithoutResult(s -> addenda.activate(id));
        assertThat(addendumStatus(id)).isEqualTo(DocumentStatus.ACTIVE);
        LocalDate parentValidTo = tx.execute(s -> contracts.get(contractId).getValidTo());
        assertThat(parentValidTo).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    void anAddendumIsOrderTolerantToo() {
        UUID id = submittedAddendum();

        completedFor(id, UUID.randomUUID(), "REVISION_REQUESTED");

        assertThat(addendumStatus(id)).isEqualTo(DocumentStatus.REVISION_REQUESTED);
        assertThat(addendumEdges(id)).containsExactly(
                "SUBMITTED->UNDER_REVIEW", "UNDER_REVIEW->REVISION_REQUESTED");
    }

    @Test
    void anAddendumEventDoesNotDisturbItsParentContract() {
        // The record key is the addendum's id, and the two documents have separate status
        // machines -- approving an addendum says nothing about the contract it amends.
        UUID contractId = activeContract();
        UUID id = submittedAddendum(contractId);

        completedFor(id, UUID.randomUUID(), "APPROVED");

        DocumentStatus parentStatus = tx.execute(s -> contracts.get(contractId).getStatus());
        assertThat(parentStatus).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void addendumEventsDedupOnTheirOwnEventIds() {
        UUID id = submittedAddendum();
        UUID eventId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        tx.executeWithoutResult(s -> listener.onCompleted(
                completedPayload(instanceId, "APPROVED"), "ADDENDUM", eventId.toString(), id));
        long rows = historyRows();

        tx.executeWithoutResult(s -> listener.onCompleted(
                completedPayload(instanceId, "APPROVED"), "ADDENDUM", eventId.toString(), id));

        assertThat(historyRows()).isEqualTo(rows);
    }

    // --- helpers ----------------------------------------------------------------------------

    private void started(UUID contractId, UUID instanceId, UUID eventId) {
        String payload = """
                {"instance_id":"%s","document_no":"HD-2026-0001","priority":"NORMAL",
                 "document_type":"CONTRACT","document_id":"%s"}"""
                .formatted(instanceId, contractId);
        tx.executeWithoutResult(s ->
                listener.onInstanceStarted(payload, "CONTRACT", eventId.toString(), contractId));
    }

    private void completed(UUID contractId, UUID instanceId, String outcome, UUID eventId) {
        tx.executeWithoutResult(s -> listener.onCompleted(
                completedPayload(instanceId, outcome), "CONTRACT", eventId.toString(), contractId));
    }

    /** Exactly what workflow-service emits — note it carries no document id; the record key does. */
    private String completedPayload(UUID instanceId, String outcome) {
        return """
                {"instance_id":"%s","outcome":"%s","document_no":"HD-2026-0001",
                 "requested_by":"Nguyen Thi Lan"}""".formatted(instanceId, outcome);
    }

    private void completedFor(UUID documentId, UUID instanceId, String outcome) {
        tx.executeWithoutResult(s -> listener.onEvent(
                completedPayload(instanceId, outcome), "workflow.completed", "ADDENDUM",
                UUID.randomUUID().toString(), documentId.toString()));
    }

    private String startedPayload(UUID instanceId, UUID documentId) {
        return """
                {"instance_id":"%s","document_no":"ADD-2026-0001","priority":"NORMAL",
                 "document_type":"ADDENDUM","document_id":"%s"}"""
                .formatted(instanceId, documentId);
    }

    private DocumentStatus addendumStatus(UUID id) {
        return tx.execute(s -> addenda.get(id).getStatus());
    }

    private List<String> addendumEdges(UUID id) {
        List<StatusHistory> all = tx.execute(s ->
                history.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(EntityType.ADDENDUM, id));
        return all.stream()
                .filter(row -> row.getFromStatus() != DocumentStatus.DRAFT)
                .map(row -> row.getFromStatus() + "->" + row.getToStatus())
                .toList();
    }

    private UUID submittedAddendum() {
        return submittedAddendum(activeContract());
    }

    private UUID submittedAddendum(UUID contractId) {
        UUID id = tx.execute(s -> addenda.create(new AddendumRequest(
                contractId, "TERM_EXTENSION", "renewal", LocalDate.of(2026, 6, 1),
                LocalDate.of(2027, 6, 30), null, null, null)).getId());
        tx.execute(s -> attachments.upload(EntityType.ADDENDUM, id,
                new MockMultipartFile("file", "annex.pdf", "application/pdf",
                        "annex".getBytes(StandardCharsets.UTF_8))));
        addenda.submit(id);
        return id;
    }

    private UUID activeContract() {
        UUID id = submittedContract();
        tx.executeWithoutResult(s -> contracts.get(id).setStatus(DocumentStatus.ACTIVE));
        return id;
    }

    private List<StatusHistory> rows(UUID contractId) {
        return tx.execute(s -> history.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
                EntityType.CONTRACT, contractId));
    }

    /** The edges after DRAFT -> SUBMITTED, as {@code FROM->TO} strings, oldest first. */
    private List<String> edgesAfterSubmit(UUID contractId) {
        return rows(contractId).stream()
                .filter(row -> row.getFromStatus() != DocumentStatus.DRAFT)
                .map(row -> row.getFromStatus() + "->" + row.getToStatus())
                .toList();
    }

    /** Bound to a typed local: {@code assertThat} has an overload for almost everything. */
    private boolean dedupped(UUID eventId) {
        Boolean exists = tx.execute(s -> processed.existsById(eventId));
        return Boolean.TRUE.equals(exists);
    }

    private long historyRows() {
        Long count = tx.execute(s -> history.count());
        return count == null ? 0 : count;
    }

    private DocumentStatus statusOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getStatus());
    }

    private UUID submittedContract() {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "initial", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
        tx.execute(s -> attachments.upload(EntityType.CONTRACT, id,
                new MockMultipartFile("file", "signed.pdf", "application/pdf",
                        "contract terms".getBytes(StandardCharsets.UTF_8))));
        contracts.submit(id);
        return id;
    }
}
