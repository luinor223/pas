package com.abclogistics.pas.contract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.scheduler.ContractStatusScheduler;
import org.springframework.dao.OptimisticLockingFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.client.WorkflowGrpcClient;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CTR-05 + D14d + D9. Date-driven transitions belong to the scheduler, never to a user, and the
 * expiry warning is a DIRECT publish with no outbox row — a lost warning re-fires next run, and
 * an outbox row would only add a second copy.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SchedulerActivatesAndExpiresTest {

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

    private static final List<GrantedAuthority> SALES_OFFICER_PERMISSIONS = Stream.of(
            "customer:read", "customer:write", "contract:read", "contract:write",
            "addendum:read", "addendum:write")
            .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

    private static final LocalDate TODAY = LocalDate.now();

    @MockitoBean WorkflowGrpcClient workflow;
    @MockitoBean KafkaTemplate<String, String> kafka;

    @Autowired ContractStatusScheduler scheduler;
    @Autowired ContractService contracts;
    @Autowired AddendumService addenda;
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // the sweeps and the warning counts are global by nature
        jdbc.execute("truncate contract.status_history, contract.outbox, contract.attachment,"
                + " contract.addendum_service, contract.addendum, contract.contract,"
                + " contract.customer_contact, contract.customer cascade");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SALES, null, SALES_OFFICER_PERMISSIONS));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // --- activation and expiry ------------------------------------------------------------------

    @Test
    void approvedBecomesActiveOnItsEffectiveDate() {
        UUID id = contract(TODAY.minusDays(1), TODAY.plusYears(1), DocumentStatus.APPROVED);

        scheduler.sweep();

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);
        // trigger S, not U: nobody pressed anything, and the history row has to say so
        assertThat(triggerOf(id, "ACTIVE")).isEqualTo("S");
    }

    @Test
    void aContractWhoseEffectiveDateHasNotArrivedIsLeftAlone() {
        UUID id = contract(TODAY.plusDays(3), TODAY.plusYears(1), DocumentStatus.APPROVED);

        scheduler.sweep();

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void activationIgnoresSigningProgress() {
        // D14e: contract activation does not consult or depend on signing state
        UUID id = contract(TODAY.minusDays(1), TODAY.plusYears(1), DocumentStatus.APPROVED);

        scheduler.sweep();

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void activeBecomesExpiredAfterItsEndDate() {
        UUID id = contract(TODAY.minusYears(1), TODAY.minusDays(1), DocumentStatus.ACTIVE);

        scheduler.sweep();

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.EXPIRED);
        assertThat(triggerOf(id, "EXPIRED")).isEqualTo("S");
    }

    @Test
    void aContractIsStillActiveOnItsLastDay() {
        // valid_to is inclusive: expiring on the day itself would cut the term a day short
        UUID id = contract(TODAY.minusYears(1), TODAY, DocumentStatus.ACTIVE);

        scheduler.sweep();

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void oneUnprocessableDocumentDoesNotStopTheSweep() {
        // an addendum whose parent was cancelled mid-approval is refused for ever; the contract
        UUID cancelled = contract(TODAY.minusYears(1), TODAY.plusYears(1), DocumentStatus.ACTIVE);
        UUID doomed = approvedAddendum(termExtension(cancelled, TODAY.minusDays(1), TODAY.plusYears(2)));
        setStatus(cancelled, DocumentStatus.CANCELLED);
        UUID healthy = contract(TODAY.minusDays(1), TODAY.plusYears(1), DocumentStatus.APPROVED);

        scheduler.sweep();

        assertThat(addendumStatusOf(doomed)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(statusOf(healthy)).isEqualTo(DocumentStatus.ACTIVE);
    }

    // --- ordering -------------------------------------------------------------------------------

    @Test
    void aRenewalAddendumIsAppliedBeforeTheParentIsExpired() {
        // The reason the four sweeps share one scheduled entry point
        UUID contractId = contract(TODAY.minusYears(1), TODAY.minusDays(1), DocumentStatus.ACTIVE);
        UUID renewal = approvedAddendum(
                termExtension(contractId, TODAY.minusDays(1), TODAY.plusMonths(6)));

        scheduler.sweep();

        assertThat(statusOf(contractId)).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(validToOf(contractId)).isEqualTo(TODAY.plusMonths(6));
        assertThat(addendumStatusOf(renewal)).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void addendaActivateInEffectiveDateOrderNotCreationOrder() {
        // A sweep that has not run for days sees both at once
        UUID contractId = contract(TODAY.minusYears(1), TODAY.plusYears(1), DocumentStatus.ACTIVE);
        approvedAddendum(paymentTerms(contractId, TODAY.minusDays(1), "NET60"));   // created first
        approvedAddendum(paymentTerms(contractId, TODAY.minusDays(10), "NET45"));  // effective earlier

        scheduler.sweep();

        assertThat(paymentTermOf(contractId)).isEqualTo("NET60");
    }

    @Test
    void twoAddendaOnTheSameEffectiveDateResolveByCreationTime() {
        // 4.3 does not say who wins a same-effective_from tie, so created_at decides
        UUID contractId = contract(TODAY.minusYears(1), TODAY.plusYears(1), DocumentStatus.ACTIVE);
        approvedAddendum(paymentTerms(contractId, TODAY.minusDays(1), "NET45"));
        approvedAddendum(paymentTerms(contractId, TODAY.minusDays(1), "NET60"));

        scheduler.sweep();

        assertThat(paymentTermOf(contractId)).isEqualTo("NET60");
    }

    // --- D9 expiry warning ----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void expiringWarningIsPublishedDirectlyWithNoOutboxRow() {
        // D9: outbox row count must be unchanged after the warning sweep.
        UUID id = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);
        int before = outboxRows();

        scheduler.publishExpiryWarnings(TODAY);

        assertThat(outboxRows()).isEqualTo(before);

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("pas.events");
        // keyed explicitly on the document: with no outbox row there is no aggregate_id to take
        assertThat(record.key()).isEqualTo(id.toString());
        assertThat(header(record, "event_type")).isEqualTo("document.expiring");
        assertThat(header(record, "document_type")).isEqualTo("CONTRACT");
        // the dedup key, in the header where every consumer already reads it (OutboxRelay does
        assertThat(header(record, "event_id")).isEqualTo(expectedEventId(id, TODAY.plusDays(10)));
        assertThat(record.value())
                .contains("\"days_left\":10")
                .contains(TODAY.plusDays(10).toString())
                .contains(contractNoOf(id));
        // the value is the §4 payload alone — the same shape OutboxRelay publishes
        assertThat(record.value())
                .doesNotContain("\"event_id\"")
                .doesNotContain("\"event_type\"")
                .doesNotContain("\"payload\"");
        JsonNode value = objectMapper.readTree(record.value());
        assertThat(value.size()).isEqualTo(4);
        assertThat(value.has("document_no")).isTrue();
        assertThat(value.has("expires_on")).isTrue();
        assertThat(value.has("days_left")).isTrue();
        assertThat(value.has("owner_user_id")).isTrue();
    }

    @Test
    void aContractExpiringBeyondTheHorizonIsNotWarnedAbout() {
        contract(TODAY.minusYears(1), TODAY.plusDays(90), DocumentStatus.ACTIVE);

        scheduler.publishExpiryWarnings(TODAY);

        verify(kafka, times(0)).send(any(ProducerRecord.class));
    }

    @Test
    void aWarningIsSentOnlyOnceForTheSameValidTo() {
        UUID id = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);

        scheduler.publishExpiryWarnings(TODAY);
        scheduler.publishExpiryWarnings(TODAY);

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(warnedForOf(id)).isEqualTo(TODAY.plusDays(10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExtensionEarnsAFreshWarningForTheNewTerm() {
        // The bug a plain "already warned" timestamp would have
        UUID contractId = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);
        scheduler.publishExpiryWarnings(TODAY);

        approvedAddendum(termExtension(contractId, TODAY, TODAY.plusDays(20)));
        scheduler.sweep();

        assertThat(validToOf(contractId)).isEqualTo(TODAY.plusDays(20));
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(1).value()).contains(TODAY.plusDays(20).toString());
        assertThat(warnedForOf(contractId)).isEqualTo(TODAY.plusDays(20));
    }

    @Test
    void aFailedSendLeavesNoStampSoTheWarningRefires() {
        // The whole justification for skipping the outbox (D9)
        UUID id = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        scheduler.publishExpiryWarnings(TODAY);

        assertThat(warnedForOf(id)).isNull();

        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        scheduler.publishExpiryWarnings(TODAY);

        assertThat(warnedForOf(id)).isEqualTo(TODAY.plusDays(10));
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEventIdIsDerivedFromTheWarningNotGeneratedFresh() {
        // The ack and the stamp cannot be atomic, so a crash between them re-sends
        UUID id = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        scheduler.publishExpiryWarnings(TODAY);           // sent, never acked, never stamped

        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        scheduler.publishExpiryWarnings(TODAY);           // the re-warn the missing stamp causes

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(captor.capture());
        assertThat(eventIdOf(captor.getAllValues().get(0)))
                .isEqualTo(eventIdOf(captor.getAllValues().get(1)));
        // and the header travels with it, or a consumer deduping off the header sees two events
        assertThat(header(captor.getAllValues().get(0), "event_id"))
                .isEqualTo(header(captor.getAllValues().get(1), "event_id"));
        // computed from the spec, not from the scheduler: the VALUE is what a consumer dedupes on
        assertThat(eventIdOf(captor.getAllValues().getFirst()))
                .isEqualTo(expectedEventId(id, TODAY.plusDays(10)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentSendsOfTheSameWarningCarryOneEventId() {
        // Two replicas at the same moment, neither stamped yet: one event twice, not two events.
        UUID id = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);

        inParallel(4, () -> scheduler.publishExpiryWarnings(TODAY));

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, atLeastOnce()).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(this::eventIdOf)
                .containsOnly(expectedEventId(id, TODAY.plusDays(10)));
        assertThat(captor.getAllValues()).extracting(r -> header(r, "event_id"))
                .containsOnly(expectedEventId(id, TODAY.plusDays(10)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewTermIsANewEventNotARepublishOfTheOldOne() {
        // The other half: an extension must NOT be deduped away as a repeat of the last warning.
        UUID id = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);
        scheduler.publishExpiryWarnings(TODAY);

        approvedAddendum(termExtension(id, TODAY, TODAY.plusDays(20)));
        scheduler.sweep();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(captor.capture());
        assertThat(eventIdOf(captor.getAllValues().get(0)))
                .isNotEqualTo(eventIdOf(captor.getAllValues().get(1)));
        assertThat(header(captor.getAllValues().get(0), "event_id"))
                .isNotEqualTo(header(captor.getAllValues().get(1), "event_id"));
    }

    // --- concurrency ----------------------------------------------------------------------------

    @Test
    void aStaleAddendumCandidateIsAQuietNoOp() {
        // The shape @Version does NOT cover: a candidate read outside the transaction
        UUID contractId = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);
        UUID addendumId = approvedAddendum(termExtension(contractId, TODAY, TODAY.plusYears(2)));
        addenda.activate(addendumId);

        assertThatCode(() -> addenda.activate(addendumId)).doesNotThrowAnyException();

        assertThat(addendumStatusOf(addendumId)).isEqualTo(DocumentStatus.ACTIVE);
        // and the second call really was a no-op: no extra row, and the effect not applied twice
        assertThat(historyRows(addendumId, "ACTIVE")).isEqualTo(1);
        assertThat(auditRows(addendumId, "STATUS_CHANGE")).isEqualTo(1);
        assertThat(validToOf(contractId)).isEqualTo(TODAY.plusYears(2));
    }

    @Test
    void concurrentAddendumActivationsApplyTheEffectExactlyOnce() {
        // @Version: one commits, the others roll back history
        UUID contractId = contract(TODAY.minusYears(1), TODAY.plusDays(10), DocumentStatus.ACTIVE);
        UUID addendumId = approvedAddendum(termExtension(contractId, TODAY, TODAY.plusYears(2)));

        List<Throwable> failures = inParallel(4, () -> addenda.activate(addendumId));

        assertThat(addendumStatusOf(addendumId)).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(validToOf(contractId)).isEqualTo(TODAY.plusYears(2));
        // the invariant that matters: one edge, one row, one audit entry — not one per thread
        assertThat(historyRows(addendumId, "ACTIVE")).isEqualTo(1);
        assertThat(auditRows(addendumId, "STATUS_CHANGE")).isEqualTo(1);
        // losers lose on the version check; nothing else may come out of here
        assertThat(failures).allSatisfy(e -> assertThat(rootIsOptimisticLock(e))
                .as("unexpected failure: %s", e).isTrue());
    }

    @Test
    void concurrentContractActivationsWriteOneHistoryRow() {
        UUID id = contract(TODAY.minusDays(1), TODAY.plusYears(1), DocumentStatus.APPROVED);

        List<Throwable> failures = inParallel(4, () -> contracts.activate(id));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(historyRows(id, "ACTIVE")).isEqualTo(1);
        assertThat(auditRows(id, "STATUS_CHANGE")).isEqualTo(1);
        assertThat(failures).allSatisfy(e -> assertThat(rootIsOptimisticLock(e))
                .as("unexpected failure: %s", e).isTrue());
    }

    @Test
    void concurrentContractExpiriesWriteOneHistoryRow() {
        UUID id = contract(TODAY.minusYears(1), TODAY.minusDays(1), DocumentStatus.ACTIVE);

        List<Throwable> failures = inParallel(4, () -> contracts.expire(id, TODAY));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.EXPIRED);
        assertThat(historyRows(id, "EXPIRED")).isEqualTo(1);
        assertThat(auditRows(id, "STATUS_CHANGE")).isEqualTo(1);
        assertThat(failures).allSatisfy(e -> assertThat(rootIsOptimisticLock(e))
                .as("unexpected failure: %s", e).isTrue());
    }

    // --- self-healing ---------------------------------------------------------------------------

    @Test
    void sweepsAreIdempotentAcrossRuns() {
        // Self-healing: running twice must not double-write history rows or re-warn.
        UUID activating = contract(TODAY.minusDays(1), TODAY.plusDays(10), DocumentStatus.APPROVED);
        UUID expiring = contract(TODAY.minusYears(1), TODAY.minusDays(1), DocumentStatus.ACTIVE);

        scheduler.sweep();
        scheduler.sweep();

        assertThat(statusOf(activating)).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(statusOf(expiring)).isEqualTo(DocumentStatus.EXPIRED);
        assertThat(historyRows(activating, "ACTIVE")).isEqualTo(1);
        assertThat(historyRows(expiring, "EXPIRED")).isEqualTo(1);
        // D17's other half: one audit row per transition, not one per sweep
        assertThat(auditRows(activating, "STATUS_CHANGE")).isEqualTo(1);
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Run {@code action} on N threads released together; returns whatever each threw. */
    private List<Throwable> inParallel(int threads, Runnable action) {
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Throwable>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        action.run();
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                }));
            }
            List<Throwable> failures = new java.util.ArrayList<>();
            for (Future<Throwable> result : results) {
                Throwable thrown = result.get(30, TimeUnit.SECONDS);
                if (thrown != null) {
                    failures.add(thrown);
                }
            }
            return failures;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A loser of the version check, however the persistence layer wrapped it. */
    private static boolean rootIsOptimisticLock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OptimisticLockingFailureException
                    || t instanceof jakarta.persistence.OptimisticLockException) {
                return true;
            }
        }
        return false;
    }

    /** The published contract, restated independently: event type, document, term being warned for. */
    private static String expectedEventId(UUID contractId, LocalDate expiresOn) {
        String name = "document.expiring:%s:%s".formatted(contractId, expiresOn);
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The header, not the value: that is where every consumer reads its dedup key (registry §4). */
    private String eventIdOf(ProducerRecord<String, String> record) {
        return header(record, "event_id");
    }

    private UUID contract(LocalDate validFrom, LocalDate validTo, DocumentStatus status) {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "sweep fixture", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                validFrom, validTo, "NET30", "MONTHLY", new BigDecimal("10"), null, null, null))
                .getId());
        setStatus(id, status);
        return id;
    }

    private void setStatus(UUID contractId, DocumentStatus status) {
        // set directly: these tests start from a status the approval path would take days to reach
        tx.executeWithoutResult(s -> contracts.get(contractId).setStatus(status));
    }

    private AddendumRequest termExtension(UUID contractId, LocalDate effectiveFrom, LocalDate newValidTo) {
        return new AddendumRequest(contractId, "TERM_EXTENSION", "renewal",
                effectiveFrom, newValidTo, null, null, null);
    }

    private AddendumRequest paymentTerms(UUID contractId, LocalDate effectiveFrom, String override) {
        return new AddendumRequest(contractId, "PAYMENT_TERMS", "terms change",
                effectiveFrom, null, override, null, null);
    }

    private UUID approvedAddendum(AddendumRequest request) {
        UUID id = tx.execute(s -> addenda.create(request).getId());
        tx.executeWithoutResult(s -> addenda.get(id).setStatus(DocumentStatus.APPROVED));
        return id;
    }

    private DocumentStatus statusOf(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getStatus());
    }

    private DocumentStatus addendumStatusOf(UUID addendumId) {
        return tx.execute(s -> addenda.get(addendumId).getStatus());
    }

    private LocalDate validToOf(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getValidTo());
    }

    private String paymentTermOf(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getPaymentTerm());
    }

    private String contractNoOf(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getContractNo());
    }

    private LocalDate warnedForOf(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getLastExpiryWarningFor());
    }

    private String triggerOf(UUID entityId, String toStatus) {
        return jdbc.queryForObject(
                "select trigger_kind from contract.status_history where entity_id = ? "
                        + "and to_status = ? order by occurred_at desc limit 1",
                String.class, entityId, toStatus);
    }

    private int historyRows(UUID entityId, String toStatus) {
        Integer count = jdbc.queryForObject(
                "select count(*) from contract.status_history where entity_id = ? and to_status = ?",
                Integer.class, entityId, toStatus);
        return count == null ? 0 : count;
    }

    private int auditRows(UUID entityId, String action) {
        Integer count = jdbc.queryForObject(
                "select count(*) from contract.outbox where aggregate_id = ? and payload::text like ?",
                Integer.class, entityId, "%" + action + "%");
        return count == null ? 0 : count;
    }

    private int outboxRows() {
        Integer count = jdbc.queryForObject("select count(*) from contract.outbox", Integer.class);
        return count == null ? 0 : count;
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
