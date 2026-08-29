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
import com.abclogistics.pas.contract.service.ContractService;

/**
 * {@code ContractInternal.GetContract} returns the STORED effective values — what billing
 * snapshots for PAY-03. After a TERM_EXTENSION or PAYMENT_TERMS addendum takes effect, the parent
 * row already carries the new values (registry §9 footnote ²), so there is no addendum replay at
 * read time and no way for a caller to see stale terms.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class GetContractReturnsEffectiveValuesTest {

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

    @Autowired
    ContractService contracts;

    @Test
    void returnsPostAddendumValidToAndPaymentTerm() {
        throw new UnsupportedOperationException("session-3 Phase B — effective values");
    }

    @Test
    void returnsServiceGroupAsTheSingleEnforcedScope() {
        // addendum_service lines are record-only and must NOT appear here: operations-service
        // validates volume entries against service_group alone (session 5 owns any change to that).
        throw new UnsupportedOperationException("session-3 Phase B — scope value");
    }

    @Test
    void expiredContractIsStillReadable() {
        // PAY-01: a contract ending 30/06 is EXPIRED by the time its June statement is built,
        // so an ACTIVE-only guard would make every contract's final period unbillable.
        throw new UnsupportedOperationException("session-3 Phase B — EXPIRED readable");
    }

}
