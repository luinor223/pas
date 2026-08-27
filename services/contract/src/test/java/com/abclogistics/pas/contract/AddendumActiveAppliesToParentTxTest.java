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
import com.abclogistics.pas.contract.service.AddendumService;

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

    @Autowired
    AddendumService addenda;

    @Test
    void termExtensionMovesParentValidTo() {
        // D14b: renewal IS a TERM_EXTENSION addendum. contract.valid_to becomes new_valid_to.
        throw new UnsupportedOperationException("session-3 Phase B — TERM_EXTENSION effect");
    }

    @Test
    void paymentTermsOverwritesParentPaymentTerm() {
        throw new UnsupportedOperationException("session-3 Phase B — PAYMENT_TERMS effect");
    }

    @Test
    void effectAndStatusFlipShareOneTransaction() {
        // A failure applying the effect must roll back the addendum's ACTIVE flip too, or the
        // parent silently keeps its old terms while the addendum claims to be in force.
        throw new UnsupportedOperationException("session-3 Phase B — single-tx effect");
    }

    @Test
    void unitPriceChangeAppliesNothingLocally() {
        // D8: the addendum carries no price data. The new figures are a pricing version Sales
        // creates afterwards -- nothing on the contract row changes here.
        throw new UnsupportedOperationException("session-3 Phase B — UNIT_PRICE_CHANGE no-op");
    }

    @Test
    void addedServiceDoesNotWidenEnforcedScope() {
        // addendum_service is record/display data. contract.service_group is unchanged, and
        // GetContract keeps returning it as the single enforced scope value.
        throw new UnsupportedOperationException("session-3 Phase B — ADDED_SERVICE record-only");
    }

}
