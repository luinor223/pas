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
    void everyTransitionWritesExactlyOneRow() {
        // Walk a full lifecycle and assert row count == transition count, with from/to chaining
        // so no edge is missing and none is duplicated.
        throw new UnsupportedOperationException("session-3 Phase B — D17 one row per edge");
    }

    @Test
    void aFailedTransactionLeavesNeitherStatusNorHistory() {
        throw new UnsupportedOperationException("session-3 Phase B — D17 atomicity");
    }

    @Test
    void historyIsAppendOnly() {
        // No update or delete path may exist. The append-only guarantee is what makes the status
        // column cross-checkable against the log.
        throw new UnsupportedOperationException("session-3 Phase B — D17 append-only");
    }

    @Test
    void triggerKindMatchesTheRegistryColumn() {
        // U for user actions, W for workflow outcomes, S for the scheduler.
        throw new UnsupportedOperationException("session-3 Phase B — D17 trigger_kind");
    }

}
