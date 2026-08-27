package com.abclogistics.pas.contract;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.domain.CustomerStatus;
import com.abclogistics.pas.contract.dto.CustomerContactRequest;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase B item 1 — customer master data (4.1).
 *
 * <p>Customers are the one aggregate here with no approval workflow: they move between ACTIVE and
 * SUSPENDED directly, so these tests assert that nothing writes {@code status_history} (D17 is for
 * documents with a state machine) while every change still lands in the audit outbox (D15).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CustomerCrudTest {

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
    }

    private static final AuthenticatedUser SALES = new AuthenticatedUser(
            UUID.randomUUID(), "lan.nt", "Nguyen Thi Lan", "SALES", List.of("SALES"));

    @Autowired CustomerService customers;
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

    // ---- create / read -------------------------------------------------------------------

    @Test
    void createAllocatesTheCodeAndStampsTheCreator() {
        Customer created = tx.execute(s -> customers.create(
                request("ACME Logistics", "0101234567")));

        assertThat(created.getCode()).matches("CUS-\\d{4}");
        assertThat(created.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(created.getCreatedBy()).isEqualTo(SALES.userId());
        assertThat(created.getCreatedByName()).isEqualTo(SALES.fullName());
        assertThat(created.getCreatedByDepartment()).isEqualTo(SALES.department());
    }

    @Test
    void codeIsServerGeneratedAndUniquePerCustomer() {
        // registry §2: the client cannot supply a code — CustomerRequest has no such field, and
        // two creates in a row must not collide.
        String first = tx.execute(s -> customers.create(request("First Co")).getCode());
        String second = tx.execute(s -> customers.create(request("Second Co")).getCode());

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void getUnknownCustomerIsNotFound() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> tx.execute(s -> customers.get(missing)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void searchMatchesNameCodeAndTaxCode() {
        Customer target = tx.execute(s -> customers.create(
                request("Vietnam Cold Chain", "0312345678")));

        assertThat(ids(search("Cold Chain", null))).contains(target.getId());
        assertThat(ids(search(target.getCode(), null))).contains(target.getId());
        assertThat(ids(search("0312345678", null))).contains(target.getId());
        // case-insensitive, per the lower(...) like lower(...) in the query
        assertThat(ids(search("vietnam cold", null))).contains(target.getId());
    }

    @Test
    void searchFiltersByStatus() {
        Customer suspended = tx.execute(s -> customers.create(request("Dormant Co")));
        tx.executeWithoutResult(s -> customers.suspend(suspended.getId(), "no longer trading"));

        assertThat(ids(search(null, "SUSPENDED"))).contains(suspended.getId());
        assertThat(ids(search(null, "ACTIVE"))).doesNotContain(suspended.getId());
    }

    // ---- update --------------------------------------------------------------------------

    @Test
    void updateChangesFieldsAndKeepsTheCode() {
        Customer created = tx.execute(s -> customers.create(request("Old Name")));
        String code = created.getCode();

        Customer updated = tx.execute(s -> customers.update(created.getId(),
                new CustomerRequest("New Name", "NN", "0100000000", "12 Le Loi",
                        "Tran Van B", "Director", "ENTERPRISE", null)));

        assertThat(updated.getCode()).isEqualTo(code);
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getTaxCode()).isEqualTo("0100000000");
        assertThat(updated.getRepresentativeName()).isEqualTo("Tran Van B");
        assertThat(updated.getUpdatedBy()).isEqualTo(SALES.userId());
    }

    // ---- contact replacement semantics ---------------------------------------------------

    @Test
    void contactsAreReplacedWholesale() {
        UUID id = tx.execute(s -> customers.create(withContacts("Contactful Co", List.of(contact("Alpha", true), contact("Beta", false)))).getId());
        assertThat(namesOf(id)).containsExactlyInAnyOrder("Alpha", "Beta");

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Contactful Co", List.of(contact("Gamma", true)))));

        // replaced, not merged — the previous two are gone
        assertThat(namesOf(id)).containsExactly("Gamma");
    }

    @Test
    void nullContactsLeavesThemAloneAndEmptyRemovesThemAll() {
        UUID id = tx.execute(s -> customers.create(withContacts("Careful Co", List.of(contact("Keep Me", true)))).getId());

        // null = "not supplied": a caller updating only the address must not wipe the contacts
        tx.executeWithoutResult(s -> customers.update(id, withContacts("Careful Co", null)));
        assertThat(namesOf(id)).containsExactly("Keep Me");

        // empty list = "remove them all" — a different request, deliberately
        tx.executeWithoutResult(s -> customers.update(id, withContacts("Careful Co", List.of())));
        assertThat(namesOf(id)).isEmpty();
    }

    @Test
    void atMostOnePrimaryContact() {
        UUID id = tx.execute(s -> customers.create(request("Two Bosses Co")).getId());
        CustomerRequest twoPrimaries = withContacts("Two Bosses Co", List.of(contact("Alpha", true), contact("Beta", true)));

        assertThatThrownBy(() -> tx.execute(s -> customers.update(id, twoPrimaries)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("primary");
    }

    @Test
    void replacingThePrimaryContactDoesNotTripTheUniqueIndex() {
        // The partial unique index is enforced per statement, so the delete of the old primary must
        // be flushed before the new one is inserted. Without that flush this fails on a duplicate.
        UUID id = tx.execute(s -> customers.create(withContacts("Handover Co", List.of(contact("Outgoing", true)))).getId());

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Handover Co", List.of(contact("Incoming", true)))));

        List<CustomerContact> contacts = tx.execute(s -> customers.contactsOf(id));
        assertThat(contacts).singleElement()
                .satisfies(c -> {
                    assertThat(c.getFullName()).isEqualTo("Incoming");
                    assertThat(c.isPrimary()).isTrue();
                });
    }

    @Test
    void contactDetailsAreCarriedThrough() {
        UUID id = tx.execute(s -> customers.create(withContacts("Detailed Co", List.of(
                new CustomerContactRequest("Le Thi C", "Head of Ops", "c@acme.vn", "0900000001", true)))).getId());

        List<CustomerContact> contacts = tx.execute(s -> customers.contactsOf(id));
        assertThat(contacts).singleElement()
                .satisfies(c -> {
                    assertThat(c.getTitle()).isEqualTo("Head of Ops");
                    assertThat(c.getEmail()).isEqualTo("c@acme.vn");
                    assertThat(c.getPhone()).isEqualTo("0900000001");
                });
    }

    @Test
    void updateAuditNamesEveryChangedFieldWithItsOldAndNewValue() {
        Customer created = tx.execute(s -> customers.create(new CustomerRequest(
                "Old Name", "ON", "0100000000", "1 Nguyen Hue", "Tran Van A", "Director",
                "SME", List.of())));

        tx.executeWithoutResult(s -> customers.update(created.getId(), new CustomerRequest(
                "Old Name", "NN", "0209999999", "99 Le Loi", "Le Thi B", "CEO",
                "ENTERPRISE", List.of())));

        String payload = newestAuditFor(created.getId());
        assertThat(payload).containsPattern(field("action", "UPDATE"));
        // every field that moved, not just the name
        assertThat(payload).contains("taxCode").contains("0100000000").contains("0209999999");
        assertThat(payload).contains("address").contains("1 Nguyen Hue").contains("99 Le Loi");
        assertThat(payload).contains("representativeName").contains("Tran Van A").contains("Le Thi B");
        assertThat(payload).contains("representativePosition").contains("Director").contains("CEO");
        assertThat(payload).contains("segment").contains("SME").contains("ENTERPRISE");
        assertThat(payload).contains("shortName").contains("ON").contains("NN");
        // and the name, which did not move, is absent
        assertThat(payload).doesNotContain("\"name\"");
    }

    @Test
    void replacingTheContactSetIsAudited() {
        // Who the counterparty actually talks to is part of the customer record; an audit trail
        // that shows only scalar fields hides a contact handover completely.
        UUID id = tx.execute(s -> customers.create(withContacts("Contacted Co",
                List.of(contact("Outgoing", true)))).getId());

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Contacted Co", List.of(contact("Incoming", true)))));

        String payload = newestAuditFor(id);
        assertThat(payload).contains("contacts").contains("Outgoing").contains("Incoming");
    }

    @Test
    void anUnchangedCustomerAuditsNoFieldChanges() {
        Customer created = tx.execute(s -> customers.create(request("Static Co", "0100000000")));

        tx.executeWithoutResult(s -> customers.update(created.getId(),
                request("Static Co", "0100000000")));

        String payload = newestAuditFor(created.getId());
        assertThat(payload).containsPattern(field("action", "UPDATE"));
        assertThat(payload).containsPattern("\"changes\"\\s*:\\s*\\{\\s*\\}");
    }

    @Test
    void aReorderedContactSetIsNotAChange() {
        // The set is what matters, not the order it arrived in.
        UUID id = tx.execute(s -> customers.create(withContacts("Ordered Co",
                List.of(contact("Alpha", true), contact("Beta", false)))).getId());

        tx.executeWithoutResult(s -> customers.update(id,
                withContacts("Ordered Co", List.of(contact("Beta", false), contact("Alpha", true)))));

        assertThat(newestAuditFor(id)).doesNotContain("contacts");
    }

    // ---- filter parsing --------------------------------------------------------------------

    @Test
    void theStatusFilterIsCaseInsensitive() {
        Customer created = tx.execute(s -> customers.create(request("Lowercase Co")));

        assertThat(ids(search(null, "active"))).contains(created.getId());
        assertThat(ids(search(null, " Active "))).contains(created.getId());
    }

    @Test
    void anUnknownStatusFilterIsA422NamingWhatIsAllowed() {
        // A bare valueOf here is an IllegalArgumentException -- a 500 for a typo'd query
        // parameter, and inconsistent with how the contract filters answer.
        assertThatThrownBy(() -> search(null, "DORMANT"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("status")
                .hasMessageContaining("SUSPENDED");
    }

    // ---- suspend / activate --------------------------------------------------------------

    @Test
    void suspendAndActivateFlipTheStatus() {
        UUID id = tx.execute(s -> customers.create(request("Flipper Co")).getId());

        tx.executeWithoutResult(s -> customers.suspend(id, "unpaid invoices"));
        assertThat(statusOf(id)).isEqualTo(CustomerStatus.SUSPENDED);

        tx.executeWithoutResult(s -> customers.activate(id));
        assertThat(statusOf(id)).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void repeatingTheCurrentStatusIsRejected() {
        UUID id = tx.execute(s -> customers.create(request("Steady Co")).getId());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> customers.activate(id)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already active");

        tx.executeWithoutResult(s -> customers.suspend(id, "first time"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> customers.suspend(id, "again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already suspended");
    }

    @Test
    void suspendAuditCarriesTheReasonAndBothStatuses() {
        UUID id = tx.execute(s -> customers.create(request("Audited Co")).getId());
        tx.executeWithoutResult(s -> customers.suspend(id, "unpaid invoices"));

        String payload = newestAuditFor(id);
        // jsonb round-trips with its own spacing, so match the field, not the formatting
        assertThat(payload).containsPattern(field("action", "SUSPEND"));
        assertThat(payload).containsPattern(field("beforeStatus", "ACTIVE"));
        assertThat(payload).containsPattern(field("afterStatus", "SUSPENDED"));
        assertThat(payload).contains("unpaid invoices");
        // D15: the actor comes from the security context, not from the request body
        assertThat(payload).contains(SALES.userId().toString());
        assertThat(payload).contains(SALES.fullName());
    }

    @Test
    void everyCustomerActionWritesOneAuditRow() {
        long before = tx.execute(s -> outbox.count());
        UUID id = tx.execute(s -> customers.create(request("Busy Co")).getId());
        tx.executeWithoutResult(s -> customers.update(id, request("Busy Co Ltd")));
        tx.executeWithoutResult(s -> customers.suspend(id, "pause"));
        tx.executeWithoutResult(s -> customers.activate(id));

        List<String> actions = tx.execute(s -> outbox.findAll().stream()
                .filter(e -> id.equals(e.getAggregateId()))
                .sorted(Comparator.comparing(OutboxEvent::getCreatedAt))
                .map(OutboxEvent::getPayload)
                .map(CustomerCrudTest::actionOf)
                .toList());

        assertThat(actions).containsExactly("CREATE", "UPDATE", "SUSPEND", "ACTIVATE");
        long after = tx.execute(s -> outbox.count());
        assertThat(after).isEqualTo(before + 4);
    }

    @Test
    void customerChangesNeverWriteStatusHistory() {
        // D17 covers documents with a state machine. A customer is not one — its ACTIVE/SUSPENDED
        // flag has no workflow behind it, and a history row here would imply one.
        long before = tx.execute(s -> history.count());
        UUID id = tx.execute(s -> customers.create(request("Historyless Co")).getId());
        tx.executeWithoutResult(s -> customers.suspend(id, "pause"));
        tx.executeWithoutResult(s -> customers.activate(id));

        long after = tx.execute(s -> history.count());
        assertThat(after).isEqualTo(before);
    }

    // ---- helpers -------------------------------------------------------------------------

    private CustomerStatus statusOf(UUID id) {
        return tx.execute(s -> customers.get(id).getStatus());
    }

    private List<Customer> search(String q, String status) {
        return tx.execute(s -> customers.search(q, status, PageRequest.of(0, 50)).getContent());
    }

    private static List<UUID> ids(List<Customer> found) {
        return found.stream().map(Customer::getId).toList();
    }

    private List<String> namesOf(UUID customerId) {
        return tx.execute(s -> customers.contactsOf(customerId).stream()
                .map(CustomerContact::getFullName).toList());
    }

    private String newestAuditFor(UUID aggregateId) {
        return tx.execute(s -> outbox.findAll().stream()
                .filter(e -> aggregateId.equals(e.getAggregateId()))
                .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                .orElseThrow()
                .getPayload());
    }

    /** A jsonb string field, tolerant of the whitespace Postgres chooses. */
    private static String field(String name, String value) {
        return "\"" + name + "\"\\s*:\\s*\"" + value + "\"";
    }

    private static String actionOf(String payload) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"action\"\\s*:\\s*\"([A-Z_]+)\"").matcher(payload);
        return m.find() ? m.group(1) : payload;
    }

    private static CustomerRequest request(String name) {
        return request(name, null);
    }

    private static CustomerRequest request(String name, String taxCode) {
        return new CustomerRequest(name, null, taxCode, null, null, null, null, null);
    }

    /** Null contacts means "not supplied"; these tests rely on that distinction. */
    private static CustomerRequest withContacts(String name, List<CustomerContactRequest> contacts) {
        return new CustomerRequest(name, null, null, null, null, null, null, contacts);
    }

    private static CustomerContactRequest contact(String fullName, boolean primary) {
        return new CustomerContactRequest(fullName, null, null, null, primary);
    }
}
