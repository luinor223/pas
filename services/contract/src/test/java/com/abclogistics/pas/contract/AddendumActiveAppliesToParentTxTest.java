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

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.AddendumServiceLine;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.ServiceGroup;
import com.abclogistics.pas.contract.dto.AddendumRequest;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.AttachmentService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.client.WorkflowGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



/**
 * registry §9 footnote ² — when an addendum flips APPROVED → ACTIVE at its {@code effective_from},
 * its effects land on the parent contract in the SAME transaction. A system action, audit-logged,
 * and NOT a CTR-07 violation: it applies an already-approved addendum rather than editing terms.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AddendumActiveAppliesToParentTxTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_contract").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static final java.nio.file.Path STORAGE = createTempStorage();

    private static java.nio.file.Path createTempStorage() {
        try {
            return java.nio.file.Files.createTempDirectory("pas-addendum").toRealPath();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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

    @Autowired AddendumService addenda;
    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired AttachmentService attachments;
    @Autowired JdbcTemplate jdbc;
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
    void termExtensionMovesParentValidTo() {
        // D14b: renewal IS a TERM_EXTENSION addendum. contract.valid_to becomes new_valid_to.
        UUID contractId = activeContract();
        UUID id = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));

        tx.executeWithoutResult(s -> addenda.activate(id));

        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void paymentTermsOverwritesParentPaymentTerm() {
        UUID contractId = activeContract();
        UUID id = approved(paymentTerms(contractId, "NET60"));

        tx.executeWithoutResult(s -> addenda.activate(id));

        assertThat(parentPaymentTerm(contractId)).isEqualTo("NET60");
        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void effectAndStatusFlipShareOneTransaction() {
        // A failure applying the effect must roll back the addendum's ACTIVE flip too, or the
        // parent silently keeps its old terms while the addendum claims to be in force.
        UUID contractId = activeContract();
        UUID id = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));
        // Reach past validation to make the effect itself fail: contract.valid_to is NOT NULL, so
        // applying a null new_valid_to dies on flush.
        jdbc.update("update contract.addendum set new_valid_to = null where id = ?", id);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> addenda.activate(id)))
                .isInstanceOf(Exception.class);

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void applyingEffectsOutsideATransactionIsRefused() {
        // The single-transaction guarantee is the point of the method, so it refuses to run
        // without one rather than half-applying an addendum. From outside the class the proxy
        // enforces MANDATORY and gets there first; the method's own assertion covers the route
        // the proxy cannot see, a call from inside this service.
        UUID contractId = activeContract();
        UUID id = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));
        Addendum detached = tx.execute(s -> addenda.get(id));

        assertThatThrownBy(() -> addenda.applyEffectsToParent(detached))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void unitPriceChangeAppliesNothingLocally() {
        // D8: the addendum carries no price data. The new figures are a pricing version Sales
        // creates afterwards -- nothing on the contract row changes here.
        UUID contractId = activeContract();
        UUID id = approved(basic(contractId, "UNIT_PRICE_CHANGE"));

        tx.executeWithoutResult(s -> addenda.activate(id));

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(parentPaymentTerm(contractId)).isEqualTo("NET30");
    }

    @Test
    void addedServiceDoesNotWidenEnforcedScope() {
        // addendum_service is record/display data. contract.service_group is unchanged, and
        // GetContract keeps returning it as the single enforced scope value.
        UUID contractId = activeContract();
        UUID id = approved(addedService(contractId));

        tx.executeWithoutResult(s -> addenda.activate(id));

        assertThat(parentServiceGroup(contractId)).isEqualTo(ServiceGroup.TRANSPORTATION);
        // Copied inside the transaction: the mapping is lazy, and the list would other-
        // wise be touched after the session that could initialise it has gone.
        List<AddendumServiceLine> lines = tx.execute(s -> List.copyOf(addenda.get(id).getServices()));
        assertThat(lines).hasSize(1);
    }

    @Test
    void anAppliedEffectIsAuditedAgainstTheParentContract() {
        // A system action that changes a contract's terms without anyone editing it needs to be
        // visible as such, or the change looks like it came from nowhere.
        UUID contractId = activeContract();
        UUID id = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));

        tx.executeWithoutResult(s -> addenda.activate(id));

        String payload = auditPayload(contractId, "ADDENDUM_APPLIED");
        assertThat(payload).contains("validTo").contains("2027-06-30");
    }

    @Test
    void anAddendumThatChangesNothingWritesNoFalseAuditRow() {
        UUID contractId = activeContract();
        UUID id = approved(basic(contractId, "UNIT_PRICE_CHANGE"));

        tx.executeWithoutResult(s -> addenda.activate(id));

        assertThat(auditRows(contractId, "ADDENDUM_APPLIED")).isZero();
    }

    // --- several addenda against one contract -------------------------------------------------

    @Test
    void aLaterAddendumNeverShortensAnExtensionAlreadyApplied() {
        // Two addenda can be approved against the same contract, and they activate in
        // effective-date order rather than approval order. Validation at create compared each
        // against the contract as it was THEN; by activation that comparison is stale.
        UUID contractId = activeContract();
        UUID longer = approved(termExtension(contractId, LocalDate.of(2028, 12, 31)));
        UUID shorter = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));

        tx.executeWithoutResult(s -> addenda.activate(longer));
        tx.executeWithoutResult(s -> addenda.activate(shorter));

        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2028, 12, 31));
    }

    @Test
    void aSupersededExtensionStillActivatesRatherThanWedging() {
        // It was approved and its date arrived, so refusing would leave it APPROVED for ever with
        // the sweep retrying it on every run. It activates; only its term effect is a no-op.
        UUID contractId = activeContract();
        UUID longer = approved(termExtension(contractId, LocalDate.of(2028, 12, 31)));
        UUID shorter = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));

        tx.executeWithoutResult(s -> addenda.activate(longer));
        tx.executeWithoutResult(s -> addenda.activate(shorter));

        assertThat(statusOf(shorter)).isEqualTo(DocumentStatus.ACTIVE);
        // and it says so: "extended nothing" is a different fact from "had no effect"
        String payload = auditPayload(contractId, "ADDENDUM_SUPERSEDED");
        assertThat(payload).contains("2027-06-30").contains("2028-12-31");
    }

    @Test
    void addendaAppliedInAscendingOrderBothTakeEffect() {
        // The ordinary case must not have been broken by the guard: a genuinely longer extension
        // applied after a shorter one still moves the parent.
        UUID contractId = activeContract();
        UUID shorter = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));
        UUID longer = approved(termExtension(contractId, LocalDate.of(2028, 12, 31)));

        tx.executeWithoutResult(s -> addenda.activate(shorter));
        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2027, 6, 30));

        tx.executeWithoutResult(s -> addenda.activate(longer));
        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2028, 12, 31));
    }

    @Test
    void anAddendumPreChecksWorkflowOutsideAnyTransactionToo() {
        // The addendum submit path is the same two-phase shape as a contract's, and has to hold
        // the same property: no connection is held across the remote call.
        UUID contractId = activeContract();
        UUID id = tx.execute(s -> addenda.create(termExtension(contractId, LocalDate.of(2027, 6, 30))).getId());
        attach(id);
        java.util.concurrent.atomic.AtomicBoolean insideTransaction = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(workflow).validateStartable("ADDENDUM");

        addenda.submit(id);

        assertThat(insideTransaction).isFalse();
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.SUBMITTED);
    }

    // --- the parent moves under the addendum's feet ------------------------------------------

    @Test
    void aCancelledParentIsRefusedAtSubmit() {
        // Create validates the parent, but approval takes days and a contract can be cancelled
        // while its addendum sits in DRAFT.
        UUID contractId = activeContract();
        UUID id = tx.execute(s -> addenda.create(termExtension(contractId, LocalDate.of(2027, 6, 30))).getId());
        attach(id);
        tx.executeWithoutResult(s -> contracts.get(contractId).setStatus(DocumentStatus.CANCELLED));

        assertThatThrownBy(() -> addenda.submit(id))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("APPROVED or ACTIVE");

        assertThat(statusOf(id)).isEqualTo(DocumentStatus.DRAFT);
    }

    @Test
    void anExpiredParentIsRefusedAtActivation() {
        // The last and most important check: an approved addendum must not rewrite the terms of a
        // contract that expired while it was being approved.
        UUID contractId = activeContract();
        UUID id = approved(termExtension(contractId, LocalDate.of(2027, 6, 30)));
        tx.executeWithoutResult(s -> contracts.get(contractId).setStatus(DocumentStatus.EXPIRED));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> addenda.activate(id)))
                .isInstanceOf(UnprocessableEntityException.class);

        // and the refusal takes the ACTIVE flip with it, so the next sweep reports the same thing
        assertThat(statusOf(id)).isEqualTo(DocumentStatus.APPROVED);
        assertThat(parentValidTo(contractId)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    // --- validation --------------------------------------------------------------------------

    @Test
    void anAddendumMayNotTakeEffectAfterTheExpiryItSets() {
        // effectiveFrom 2026-09-01 with newValidTo 2026-08-01 would activate onto a date already
        // past, and the D14d sweep would expire the contract on its very next run.
        UUID contractId = activeContract();
        AddendumRequest request = new AddendumRequest(contractId, "TERM_EXTENSION", "renewal",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 30), null, null, null);

        assertThatThrownBy(() -> tx.execute(s -> addenda.create(request)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("effectiveFrom");
    }

    @Test
    void anAddendumMayNotTakeEffectAfterTheParentHasExpired() {
        // Renewing an already-EXPIRED contract is not a §9 edge in this session, so an addendum
        // cannot be scheduled to land after the parent's current expiry.
        UUID contractId = activeContract();
        AddendumRequest request = new AddendumRequest(contractId, "TERM_EXTENSION", "renewal",
                LocalDate.of(2027, 1, 15), LocalDate.of(2027, 6, 30), null, null, null);

        assertThatThrownBy(() -> tx.execute(s -> addenda.create(request)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("already have expired");
    }

    @Test
    void aShorteningTermExtensionIsStillRefused() {
        UUID contractId = activeContract();
        // effectiveFrom before newValidTo, so the request is internally coherent and it is the
        // comparison against the contract that has to refuse it.
        AddendumRequest request = new AddendumRequest(contractId, "TERM_EXTENSION", "renewal",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 31), null, null, null);

        assertThatThrownBy(() -> tx.execute(s -> addenda.create(request)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("does not shorten");
    }

    // --- update -------------------------------------------------------------------------------

    @Test
    void retypingAnAddendumDropsTheServiceLinesTheOldTypeCarried() {
        // The scalar overrides are already cleared on a retype; the rows have to go the same way,
        // or they stay attached, invisible to the new type, and reappear if it is retyped back.
        UUID contractId = activeContract();
        UUID id = tx.execute(s -> addenda.create(addedService(contractId)).getId());
        Integer version = tx.execute(s -> addenda.get(id).getVersion());

        tx.executeWithoutResult(s -> addenda.update(id, new AddendumRequest(
                contractId, "PAYMENT_TERMS", "terms change", LocalDate.of(2026, 6, 1),
                null, "NET60", null, version)));

        List<AddendumServiceLine> lines = tx.execute(s -> List.copyOf(addenda.get(id).getServices()));
        assertThat(lines).isEmpty();
        String override = tx.execute(s -> addenda.get(id).getPaymentTermOverride());
        assertThat(override).isEqualTo("NET60");
    }

    @Test
    void aNonAddedServiceTypeCannotSmuggleInServiceLines() {
        UUID contractId = activeContract();
        AddendumRequest request = new AddendumRequest(contractId, "PAYMENT_TERMS", "terms",
                LocalDate.of(2026, 6, 1), null, "NET60",
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing", null, null)),
                null);

        UUID id = tx.execute(s -> addenda.create(request).getId());

        List<AddendumServiceLine> lines = tx.execute(s -> List.copyOf(addenda.get(id).getServices()));
        assertThat(lines).isEmpty();
    }

    @Test
    void anAddendumCannotBeMovedToAnotherContract() {
        // Answering 200 to a reassignment that did not happen is worse than refusing it: the
        // addendum would still apply its effects to the contract the client thinks it left.
        UUID contractId = activeContract();
        UUID otherContractId = activeContract();
        UUID id = tx.execute(s -> addenda.create(termExtension(contractId, LocalDate.of(2027, 6, 30))).getId());
        Integer version = tx.execute(s -> addenda.get(id).getVersion());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> addenda.update(id,
                new AddendumRequest(otherContractId, "TERM_EXTENSION", "renewal",
                        LocalDate.of(2026, 6, 1), LocalDate.of(2027, 6, 30), null, null, version))))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("cannot be moved");

        UUID parentId = tx.execute(s -> addenda.get(id).getContract().getId());
        assertThat(parentId).isEqualTo(contractId);
    }

    @Test
    void aServiceLineEditIsAuditedDownToItsScopeNote() {
        // Snapshotting only code and name would report "nothing changed" for an edit to a unit or
        // a scope note, which is the one record of what the line used to say.
        UUID contractId = activeContract();
        UUID id = tx.execute(s -> addenda.create(addedService(contractId)).getId());
        Integer version = tx.execute(s -> addenda.get(id).getVersion());

        tx.executeWithoutResult(s -> addenda.update(id, new AddendumRequest(
                contractId, "ADDED_SERVICE", "new service", LocalDate.of(2026, 6, 1), null, null,
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing", "pallet",
                        "inbound only")),
                version)));

        String payload = auditPayload(id, "UPDATE");
        assertThat(payload).contains("services").contains("pallet").contains("inbound only");
    }

    @Test
    void aScopeNoteOfTheLiteralWordNullIsNotTheSameAsNoScopeNote() {
        // Joining the snapshot into a string turned null into "null", so clearing a scope note
        // that literally read "null" produced no audit difference at all.
        UUID contractId = activeContract();
        UUID id = tx.execute(s -> addenda.create(new AddendumRequest(
                contractId, "ADDED_SERVICE", "new service", LocalDate.of(2026, 6, 1), null, null,
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing", "m2", "null")),
                null)).getId());
        Integer version = tx.execute(s -> addenda.get(id).getVersion());

        tx.executeWithoutResult(s -> addenda.update(id, new AddendumRequest(
                contractId, "ADDED_SERVICE", "new service", LocalDate.of(2026, 6, 1), null, null,
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing", "m2", null)),
                version)));

        String payload = auditPayload(id, "UPDATE");
        // detected as a change, and the row says which way round it went: the old value is the
        // quoted string, the new one a JSON null
        assertThat(payload).contains("services");
        assertThat(payload).containsPattern("\"scopeNote\"\\s*:\\s*\"null\"");
        assertThat(payload).containsPattern("\"scopeNote\"\\s*:\\s*null");
    }

    @Test
    void aValueContainingTheDelimiterDoesNotCollapseTwoLines() {
        // Any delimiter can appear inside a value, so a joined representation can make two
        // different sets of lines compare equal. Structured entries cannot.
        UUID contractId = activeContract();
        UUID id = tx.execute(s -> addenda.create(new AddendumRequest(
                contractId, "ADDED_SERVICE", "new service", LocalDate.of(2026, 6, 1), null, null,
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing", "m2", "a|b")),
                null)).getId());
        Integer version = tx.execute(s -> addenda.get(id).getVersion());

        tx.executeWithoutResult(s -> addenda.update(id, new AddendumRequest(
                contractId, "ADDED_SERVICE", "new service", LocalDate.of(2026, 6, 1), null, null,
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing", "m2|a", "b")),
                version)));

        String payload = auditPayload(id, "UPDATE");
        assertThat(payload).contains("services").contains("a|b").contains("m2|a");
    }

    // --- helpers ----------------------------------------------------------------------------

    private AddendumRequest termExtension(UUID contractId, LocalDate newValidTo) {
        return new AddendumRequest(contractId, "TERM_EXTENSION", "renewal",
                LocalDate.of(2026, 6, 1), newValidTo, null, null, null);
    }

    private AddendumRequest paymentTerms(UUID contractId, String override) {
        return new AddendumRequest(contractId, "PAYMENT_TERMS", "terms change",
                LocalDate.of(2026, 6, 1), null, override, null, null);
    }

    private AddendumRequest addedService(UUID contractId) {
        return new AddendumRequest(contractId, "ADDED_SERVICE", "new service",
                LocalDate.of(2026, 6, 1), null, null,
                List.of(new AddendumRequest.ServiceLine(null, "WH-01", "Warehousing", "m2", null)),
                null);
    }

    private AddendumRequest basic(UUID contractId, String changeType) {
        return new AddendumRequest(contractId, changeType, "prices move",
                LocalDate.of(2026, 6, 1), null, null, null, null);
    }

    /** Creates the addendum and forces it to APPROVED, which is where the D14d sweep picks it up. */
    private UUID approved(AddendumRequest request) {
        UUID id = tx.execute(s -> addenda.create(request).getId());
        tx.executeWithoutResult(s -> addenda.get(id).setStatus(DocumentStatus.APPROVED));
        return id;
    }

    private void attach(UUID addendumId) {
        tx.execute(s -> attachments.upload(EntityType.ADDENDUM, addendumId,
                new MockMultipartFile("file", "annex.pdf", "application/pdf",
                        "annex".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private UUID activeContract() {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
        UUID id = tx.execute(s -> contracts.create(new ContractRequest(
                customerId, "initial", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "NET30", "MONTHLY", new BigDecimal("10"), null, null, null)).getId());
        tx.executeWithoutResult(s -> contracts.get(id).setStatus(DocumentStatus.ACTIVE));
        return id;
    }

    private DocumentStatus statusOf(UUID addendumId) {
        return tx.execute(s -> addenda.get(addendumId).getStatus());
    }

    private LocalDate parentValidTo(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getValidTo());
    }

    private String parentPaymentTerm(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getPaymentTerm());
    }

    private ServiceGroup parentServiceGroup(UUID contractId) {
        return tx.execute(s -> contracts.get(contractId).getServiceGroup());
    }

    private int auditRows(UUID entityId, String action) {
        Integer count = jdbc.queryForObject(
                "select count(*) from contract.outbox where aggregate_id = ? and payload::text like ?",
                Integer.class, entityId, "%" + action + "%");
        return count == null ? 0 : count;
    }

    private String auditPayload(UUID entityId, String action) {
        return jdbc.queryForObject(
                "select payload::text from contract.outbox where aggregate_id = ? "
                        + "and payload::text like ? limit 1",
                String.class, entityId, "%" + action + "%");
    }
}
