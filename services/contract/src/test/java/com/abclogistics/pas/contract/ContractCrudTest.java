package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.BillingCycle;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.ServiceGroup;
import com.abclogistics.pas.contract.dto.ContractRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase B item 3 — contract CRUD beyond the CTR-01 guard: reference-value validation before
 * anything is persisted, the documented search filters, and the field-level audit trail an
 * "UPDATE" row is useless without.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class ContractCrudTest {

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
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    @Autowired ContractService contracts;
    @Autowired CustomerService customers;
    @Autowired OutboxRepository outbox;
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

    // ---- reference-value validation ------------------------------------------------------

    @Test
    void anUnknownServiceGroupIsRejectedBeforeAnythingIsWritten() {
        long numbersBefore = countContracts();
        Req bad = request(newCustomer()).withServiceGroup("AIR_FREIGHT");

        assertThatThrownBy(() -> tx.execute(s -> contracts.create(bad.toRequest())))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("serviceGroup")
                .hasMessageContaining("TRANSPORTATION"); // the message names what IS allowed

        // and it did not burn a contract number on the way to failing
        assertThat(countContracts()).isEqualTo(numbersBefore);
    }

    @Test
    void anUnknownCurrencyIsRejected() {
        Req bad = request(newCustomer()).withCurrency("VDN");

        assertThatThrownBy(() -> tx.execute(s -> contracts.create(bad.toRequest())))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    void anUnsupportedBillingCycleIsRejected() {
        // The DDL CHECK allows MONTHLY only; this must fail as a 422 naming the allowed set, not
        // as a constraint violation surfacing from Postgres.
        Req bad = request(newCustomer()).withBillingCycle("WEEKLY");

        assertThatThrownBy(() -> tx.execute(s -> contracts.create(bad.toRequest())))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("billingCycle")
                .hasMessageContaining("MONTHLY");
    }

    @Test
    void referenceValuesAreNormalisedNotJustAccepted() {
        Contract created = tx.execute(s -> contracts.create(
                request(newCustomer()).withServiceGroup("transportation").withCurrency("usd").toRequest()));

        assertThat(created.getServiceGroup()).isEqualTo(ServiceGroup.TRANSPORTATION);
        assertThat(created.getCurrency()).isEqualTo("USD");
    }

    @Test
    void omittedCurrencyAndBillingCycleKeepTheirDefaults() {
        // Both columns are NOT NULL with a DDL default, so "not supplied" must mean "leave it",
        // never "set it to null".
        Contract created = tx.execute(s -> contracts.create(
                request(newCustomer()).withCurrency(null).withBillingCycle(null).toRequest()));

        assertThat(created.getCurrency()).isEqualTo("VND");
        assertThat(created.getBillingCycle()).isEqualTo(BillingCycle.MONTHLY);

        Contract updated = tx.execute(s -> contracts.update(created.getId(),
                request(created.getCustomer().getId())
                        .withCurrency(null).withBillingCycle(null).withVersion(created.getVersion()).toRequest()));

        assertThat(updated.getCurrency()).isEqualTo("VND");
        assertThat(updated.getBillingCycle()).isEqualTo(BillingCycle.MONTHLY);
    }

    @Test
    void anInvalidValidityWindowIsRejected() {
        Req bad = request(newCustomer())
                .withWindow(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> tx.execute(s -> contracts.create(bad.toRequest())))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("CTR-02");
    }

    @Test
    void anUnparseableFilterIsTheCallersMistakeNotAServerError() {
        // A raw valueOf here would be an IllegalArgumentException, i.e. a 500 for a typo'd query
        // parameter.
        assertThatThrownBy(() -> search(null, "NOT_A_STATUS", null, null))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> search(null, null, "NOT_A_GROUP", null))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("serviceGroup");
    }

    // ---- documented search filters -------------------------------------------------------

    @Test
    void serviceGroupFilterSelectsOnlyThatGroup() {
        UUID customerId = newCustomer();
        Contract transport = tx.execute(s -> contracts.create(
                request(customerId).withServiceGroup("TRANSPORTATION").toRequest()));
        Contract warehouse = tx.execute(s -> contracts.create(
                request(customerId).withServiceGroup("WAREHOUSING").toRequest()));

        List<UUID> found = ids(search(customerId, null, "WAREHOUSING", null));
        assertThat(found).contains(warehouse.getId()).doesNotContain(transport.getId());
    }

    @Test
    void freeTextSearchesContractNoDescriptionAndCustomerName() {
        UUID customerId = tx.execute(s -> customers.create(new CustomerRequest(
                "Mekong Delta Freight", null, null, null, null, null, null, List.of())).getId());
        Contract created = tx.execute(s -> contracts.create(
                request(customerId).withDescription("quarterly reefer haulage").toRequest()));

        assertThat(ids(search(null, null, null, created.getContractNo()))).contains(created.getId());
        assertThat(ids(search(null, null, null, "reefer haulage"))).contains(created.getId());
        assertThat(ids(search(null, null, null, "Mekong Delta"))).contains(created.getId());
        // case-insensitive, like the customer search
        assertThat(ids(search(null, null, null, "REEFER"))).contains(created.getId());
        assertThat(ids(search(null, null, null, "nothing matches this"))).isEmpty();
    }

    @Test
    void filtersCombineRatherThanOverrideEachOther() {
        UUID a = newCustomer();
        UUID b = newCustomer();
        Contract wanted = tx.execute(s -> contracts.create(
                request(a).withServiceGroup("WAREHOUSING").withDescription("cold storage").toRequest()));
        tx.execute(s -> contracts.create(request(b).withServiceGroup("WAREHOUSING").withDescription("cold storage").toRequest()));

        List<UUID> found = ids(search(a, "DRAFT", "WAREHOUSING", "cold storage"));
        assertThat(found).containsExactly(wanted.getId());
    }

    @Test
    void blankFiltersAreTreatedAsAbsent() {
        UUID customerId = newCustomer();
        Contract created = tx.execute(s -> contracts.create(request(customerId).toRequest()));

        assertThat(ids(search(customerId, "  ", "", "   "))).contains(created.getId());
    }

    // ---- field-level audit ----------------------------------------------------------------

    @Test
    void updateAuditNamesEveryChangedFieldWithItsOldAndNewValue() {
        Contract created = tx.execute(s -> contracts.create(request(newCustomer()).toRequest()));

        tx.executeWithoutResult(s -> contracts.update(created.getId(),
                request(created.getCustomer().getId())
                        .withDescription("renegotiated scope")
                        .withValue(new BigDecimal("2500000"))
                        .withVersion(created.getVersion()).toRequest()));

        String payload = newestAuditFor(created.getId());
        assertThat(payload).containsPattern(field("action", "UPDATE"));
        assertThat(payload).contains("description").contains("initial").contains("renegotiated scope");
        assertThat(payload).contains("value").contains("2500000");
        assertThat(payload).containsPattern("\"from\"\\s*:").containsPattern("\"to\"\\s*:");
    }

    @Test
    void unchangedFieldsAreNotReportedAsChanges() {
        Contract created = tx.execute(s -> contracts.create(request(newCustomer()).toRequest()));

        tx.executeWithoutResult(s -> contracts.update(created.getId(),
                request(created.getCustomer().getId())
                        .withDescription("only this moved")
                        .withVersion(created.getVersion()).toRequest()));

        String payload = newestAuditFor(created.getId());
        assertThat(payload).contains("description");
        // an audit row claiming everything changed is as useless as one claiming nothing did
        assertThat(payload).doesNotContain("serviceGroup").doesNotContain("validFrom");
    }

    @Test
    void aRescaledDecimalIsNotAChange() {
        // 10 and 10.00 are the same VAT rate. BigDecimal.equals disagrees, and a diff built on it
        // would report a change on every save.
        Contract created = tx.execute(s -> contracts.create(
                request(newCustomer()).withVatRate(new BigDecimal("10")).toRequest()));

        tx.executeWithoutResult(s -> contracts.update(created.getId(),
                request(created.getCustomer().getId())
                        .withVatRate(new BigDecimal("10.00"))
                        .withVersion(created.getVersion()).toRequest()));

        assertThat(newestAuditFor(created.getId())).doesNotContain("vatRate");
    }

    @Test
    void clearingAFieldIsAuditedAsAChangeToNull() {
        Contract created = tx.execute(s -> contracts.create(
                request(newCustomer()).withVatRate(new BigDecimal("10")).toRequest()));

        tx.executeWithoutResult(s -> contracts.update(created.getId(),
                request(created.getCustomer().getId())
                        .withVatRate(null).withVersion(created.getVersion()).toRequest()));

        String payload = newestAuditFor(created.getId());
        // null means "not stated" and is recorded as such — not as the string "null", and never
        // silently coerced to 0.
        assertThat(payload).contains("vatRate");
        assertThat(payload).containsPattern("\"to\"\\s*:\\s*null");
    }

    @Test
    void anEditOutOfRevisionRequestedIsAuditedAsBothAnUpdateAndATransition() {
        Contract created = tx.execute(s -> contracts.create(request(newCustomer()).toRequest()));
        tx.executeWithoutResult(s -> contracts.get(created.getId())
                .setStatus(DocumentStatus.REVISION_REQUESTED));
        // forcing the status is itself a write, so re-read the lock the edit has to match
        Integer current = tx.execute(s -> contracts.get(created.getId()).getVersion());

        tx.executeWithoutResult(s -> contracts.update(created.getId(),
                request(created.getCustomer().getId())
                        .withDescription("addressed the comments")
                        .withVersion(current).toRequest()));

        // The field change and the status change are two separate facts; recording only the
        // transition loses what was actually edited in response to the review.
        List<String> actions = auditActionsFor(created.getId());
        assertThat(actions).contains("UPDATE");
        assertThat(newestAuditFor(created.getId())).isNotNull();
        assertThat(payloadsFor(created.getId()))
                .anySatisfy(p -> assertThat(p).contains("addressed the comments"));
        assertThat(payloadsFor(created.getId()))
                .anySatisfy(p -> assertThat(p).contains("REVISION_REQUESTED").contains("DRAFT"));
    }

    @Test
    void reassigningTheCustomerIsItsOwnAuditRow() {
        UUID original = newCustomer();
        Contract created = tx.execute(s -> contracts.create(request(original).toRequest()));
        UUID replacement = newCustomer();

        tx.executeWithoutResult(s -> contracts.update(created.getId(),
                request(replacement).withVersion(created.getVersion()).toRequest()));

        assertThat(auditActionsFor(created.getId())).contains("REASSIGN_CUSTOMER");
    }

    @Test
    void createAuditCarriesTheTermsTheContractStartedWith() {
        Contract created = tx.execute(s -> contracts.create(request(newCustomer()).toRequest()));

        String payload = payloadsFor(created.getId()).get(0);
        assertThat(payload).containsPattern(field("action", "CREATE"));
        assertThat(payload).contains("serviceGroup").contains("TRANSPORTATION");
        assertThat(payload).contains("validFrom").contains("2026-01-01");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private UUID newCustomer() {
        return tx.execute(s -> customers.create(new CustomerRequest(
                "ACME Logistics", null, null, null, null, null, null, List.of())).getId());
    }

    private List<Contract> search(UUID customerId, String status, String serviceGroup, String q) {
        return tx.execute(s -> contracts.search(customerId, status, serviceGroup, q,
                PageRequest.of(0, 50)).getContent());
    }

    private long countContracts() {
        return tx.execute(s -> contracts.search(null, null, null, null,
                PageRequest.of(0, 1)).getTotalElements());
    }

    private static List<UUID> ids(List<Contract> found) {
        return found.stream().map(Contract::getId).toList();
    }

    private List<String> payloadsFor(UUID aggregateId) {
        return tx.execute(s -> outbox.findAll().stream()
                .filter(e -> aggregateId.equals(e.getAggregateId()))
                .sorted(Comparator.comparing(OutboxEvent::getCreatedAt))
                .map(OutboxEvent::getPayload)
                .toList());
    }

    private String newestAuditFor(UUID aggregateId) {
        List<String> payloads = payloadsFor(aggregateId);
        return payloads.get(payloads.size() - 1);
    }

    private List<String> auditActionsFor(UUID aggregateId) {
        return payloadsFor(aggregateId).stream().map(ContractCrudTest::actionOf).toList();
    }

    private static String actionOf(String payload) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"action\"\\s*:\\s*\"([A-Z_]+)\"").matcher(payload);
        return m.find() ? m.group(1) : payload;
    }

    /** A jsonb string field, tolerant of the whitespace Postgres chooses. */
    private static String field(String name, String value) {
        return "\"" + name + "\"\\s*:\\s*\"" + value + "\"";
    }

    private static Req request(UUID customerId) {
        return new Req(customerId, "initial", "TRANSPORTATION", new BigDecimal("1000000"), "VND",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "MONTHLY",
                new BigDecimal("10"), null);
    }

    /**
     * A ContractRequest with named withers, so each test states only the field it is about.
     * {@link #toRequest()} keeps the positional constructor in exactly one place.
     */
    private record Req(UUID customerId, String description, String serviceGroup, BigDecimal value,
                       String currency, LocalDate validFrom, LocalDate validTo, String billingCycle,
                       BigDecimal vatRate, Integer version) {

        Req withDescription(String v) {
            return new Req(customerId, v, serviceGroup, value, currency, validFrom, validTo, billingCycle, vatRate, version);
        }

        Req withServiceGroup(String v) {
            return new Req(customerId, description, v, value, currency, validFrom, validTo, billingCycle, vatRate, version);
        }

        Req withValue(BigDecimal v) {
            return new Req(customerId, description, serviceGroup, v, currency, validFrom, validTo, billingCycle, vatRate, version);
        }

        Req withCurrency(String v) {
            return new Req(customerId, description, serviceGroup, value, v, validFrom, validTo, billingCycle, vatRate, version);
        }

        Req withWindow(LocalDate from, LocalDate to) {
            return new Req(customerId, description, serviceGroup, value, currency, from, to, billingCycle, vatRate, version);
        }

        Req withBillingCycle(String v) {
            return new Req(customerId, description, serviceGroup, value, currency, validFrom, validTo, v, vatRate, version);
        }

        Req withVatRate(BigDecimal v) {
            return new Req(customerId, description, serviceGroup, value, currency, validFrom, validTo, billingCycle, v, version);
        }

        Req withVersion(Integer v) {
            return new Req(customerId, description, serviceGroup, value, currency, validFrom, validTo, billingCycle, vatRate, v);
        }

        ContractRequest toRequest() {
            return new ContractRequest(customerId, description, serviceGroup, value, currency,
                    validFrom, validTo, "NET30", billingCycle, vatRate, null, null, version);
        }
    }
}
