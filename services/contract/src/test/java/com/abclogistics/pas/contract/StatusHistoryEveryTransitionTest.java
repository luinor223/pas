package com.abclogistics.pas.contract;

import jakarta.persistence.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.listener.WorkflowEventListener;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.StatusTransitionService;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * D17 — every status change writes exactly one status_history row in the same transaction.
 * The status column is a cache of the newest row; a status change with no row is a bug, and the
 * two are reconcilable by construction.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class StatusHistoryEveryTransitionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static final Path STORAGE = createTempStorage();

    private static Path createTempStorage() {
        try {
            return Files.createTempDirectory("pas-history").toRealPath();
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

    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    private static final LocalDate TODAY = LocalDate.now();

    @MockitoBean WorkflowGrpcClient workflow;

    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired AttachmentService attachments;
    @Autowired StatusTransitionService transitions;
    @Autowired StatusHistoryRepository history;
    @Autowired WorkflowEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate contract.status_history, contract.outbox, contract.attachment,"
                + " contract.addendum_service, contract.addendum, contract.contract,"
                + " contract.customer_contact, contract.customer cascade");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, SALES_OFFICER_PERMISSIONS));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void everyTransitionWritesExactlyOneRow() {
        // Walk a full lifecycle and assert row count == transition count, with from/to chaining
        // so no edge is missing and none is duplicated.
        UUID id = fullLifecycle();

        List<StatusHistory> rows = historyOf(id);

        assertThat(rows).extracting(StatusHistory::getFromStatus, StatusHistory::getToStatus)
                .containsExactly(
                        tuple(DocumentStatus.DRAFT, DocumentStatus.SUBMITTED),
                        tuple(DocumentStatus.SUBMITTED, DocumentStatus.UNDER_REVIEW),
                        tuple(DocumentStatus.UNDER_REVIEW, DocumentStatus.APPROVED),
                        tuple(DocumentStatus.APPROVED, DocumentStatus.ACTIVE),
                        tuple(DocumentStatus.ACTIVE, DocumentStatus.EXPIRED));

        // the chain is closed: every row's from is the previous row's to, so a missing edge or a
        // duplicated one shows up as a break rather than as a count that happens to still add up
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).getFromStatus()).isEqualTo(rows.get(i - 1).getToStatus());
        }
        // and the status column is exactly the newest row's to — the cache D17 says it is
        assertThat(statusOf(id)).isEqualTo(rows.getLast().getToStatus());
    }

    @Test
    void aFailedTransactionLeavesNeitherStatusNorHistory() {
        // The row and the status move together or not at all. A history row surviving a rolled
        // back status change would claim a transition that never happened.
        UUID id = draftContract();
        String no = contractNoOf(id);
        // the baseline, not zero: creating the customer and the contract audited themselves
        long auditRowsBefore = outboxRows();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            transitions.transition(EntityType.CONTRACT, id, no,
                    DocumentStatus.DRAFT, DocumentStatus.SUBMITTED, TriggerKind.U, null, "submit");
            contracts.get(id).setStatus(DocumentStatus.SUBMITTED);
            throw new IllegalStateException("something later in the same transaction failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.DRAFT);
        assertThat(historyOf(id)).isEmpty();
        // the audit row rides the same transaction, so it rolled back with everything else
        assertThat(outboxRows()).isEqualTo(auditRowsBefore);
    }

    @Test
    void historyIsAppendOnly() {
        // No update or delete path may exist. The append-only guarantee is what makes the status
        // column cross-checkable against the log.
        UUID id = draftContract();
        String no = contractNoOf(id);
        tx.executeWithoutResult(s -> transitions.transition(EntityType.CONTRACT, id, no,
                DocumentStatus.DRAFT, DocumentStatus.SUBMITTED, TriggerKind.U, null, "submit"));
        tx.executeWithoutResult(s -> contracts.get(id).setStatus(DocumentStatus.SUBMITTED));
        StatusHistory first = historyOf(id).getFirst();

        // the next transition appends; it does not rewrite what is already there
        tx.executeWithoutResult(s -> transitions.transition(EntityType.CONTRACT, id, no,
                DocumentStatus.SUBMITTED, DocumentStatus.CANCELLED, TriggerKind.U, null, "cancel"));

        assertThat(historyOf(id)).hasSize(2);
        StatusHistory reread = historyOf(id).getFirst();
        assertThat(reread.getId()).isEqualTo(first.getId());
        assertThat(reread.getFromStatus()).isEqualTo(first.getFromStatus());
        assertThat(reread.getToStatus()).isEqualTo(first.getToStatus());
        assertThat(reread.getOccurredAt()).isEqualTo(first.getOccurredAt());

        // structural, not incidental: every mapped column is updatable = false, so a dirty check
        // cannot rewrite a row even if a future caller mutates a loaded entity, and the class
        // exposes no setter to mutate it with in the first place
        for (Field field : StatusHistory.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                assertThat(column.updatable())
                        .as("StatusHistory.%s must be updatable = false (D17 append-only)",
                                field.getName())
                        .isFalse();
            }
        }
        assertThat(Stream.of(StatusHistory.class.getDeclaredMethods()).map(Method::getName))
                .as("StatusHistory must expose no mutator (D17 append-only)")
                .noneMatch(name -> name.startsWith("set"));
    }

    @Test
    void theRepositoryOffersNoWayToDeleteARow() {
        // The other half of append-only, and the half a column mapping cannot give: updatable =
        // false stops a row being rewritten, not removed. StatusHistoryRepository therefore
        // declares its surface instead of inheriting JpaRepository's — which would hand every
        // caller delete / deleteAll / deleteAllInBatch, and a transition log anything can delete
        // from cannot be what the status column is checked against.
        assertThat(Stream.of(StatusHistoryRepository.class.getMethods()).map(Method::getName))
                .as("StatusHistoryRepository must expose no delete (D17 append-only)")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("delete")
                        || name.toLowerCase(Locale.ROOT).contains("remove"))
                // and the surface really is the declared one, not an inherited CRUD interface
                .containsExactlyInAnyOrder("save", "findByEntityTypeAndEntityIdOrderByOccurredAtAsc",
                        "count");
    }

    @Test
    void triggerKindMatchesTheRegistryColumn() {
        // U for user actions, W for workflow outcomes, S for the scheduler.
        UUID id = fullLifecycle();

        assertThat(historyOf(id)).extracting(StatusHistory::getTriggerKind)
                .containsExactly(
                        TriggerKind.U,   // submit — a person pressed it
                        TriggerKind.W,   // instance_started
                        TriggerKind.W,   // completed/APPROVED
                        TriggerKind.S,   // D14d activation
                        TriggerKind.S);  // D14d expiry

        // and the actor travels with the trigger: a user edge names them, a scheduler edge is
        // "system" with no id, which is what the History tab renders (D15)
        List<StatusHistory> rows = historyOf(id);
        assertThat(rows.getFirst().getActorId()).isEqualTo(SALES.userId());
        assertThat(rows.getFirst().getActorName()).isEqualTo("Nguyen Thi Lan");
        assertThat(rows.getLast().getActorId()).isNull();
        assertThat(rows.getLast().getActorName()).isEqualTo("system");
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED → ACTIVE → EXPIRED, each edge through the code
     * that really drives it: the user's submit, the two workflow events, and the D14d sweep. The
     * scheduler edges run unauthenticated, as they do in production.
     */
    private UUID fullLifecycle() {
        UUID id = draftContract();
        tx.execute(s -> attachments.upload(EntityType.CONTRACT, id,
                new MockMultipartFile("file", "signed.pdf", "application/pdf",
                        "contract terms".getBytes(StandardCharsets.UTF_8))));
        contracts.submit(id);                                          // DRAFT -> SUBMITTED (U)

        UUID instanceId = UUID.randomUUID();
        tx.executeWithoutResult(s -> listener.onInstanceStarted(
                """
                {"instance_id":"%s"}""".formatted(instanceId),
                "CONTRACT", UUID.randomUUID().toString(), id));         // -> UNDER_REVIEW (W)
        tx.executeWithoutResult(s -> listener.onCompleted(
                """
                {"instance_id":"%s","outcome":"APPROVED"}""".formatted(instanceId),
                "CONTRACT", UUID.randomUUID().toString(), id));         // -> APPROVED (W)

        // the scheduler acts as nobody; clearing the context is what makes the actor assertions
        // above mean something rather than inheriting the submitter
        SecurityContextHolder.clearContext();
        contracts.activate(id);                                        // -> ACTIVE (S)
        // brought forward rather than created in the past: the contract has to be creatable first
        tx.executeWithoutResult(s -> contracts.get(id).setValidTo(TODAY.minusDays(1)));
        contracts.expire(id, TODAY);                                   // -> EXPIRED (S)
        return id;
    }

    private UUID draftContract() {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        return tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "lifecycle", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                TODAY.minusDays(1), TODAY.plusYears(1),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
    }

    private List<StatusHistory> historyOf(UUID id) {
        return tx.execute(s -> history.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
                EntityType.CONTRACT, id));
    }

    private DocumentStatus statusOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getStatus());
    }

    private String contractNoOf(UUID id) {
        return tx.execute(s -> contracts.get(id).getContractNo());
    }

    private long outboxRows() {
        Long count = jdbc.queryForObject("select count(*) from contract.outbox", Long.class);
        return count == null ? 0 : count;
    }
}
