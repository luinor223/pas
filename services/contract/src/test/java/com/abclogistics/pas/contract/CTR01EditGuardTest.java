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
 * CTR-01 — a contract is editable only in DRAFT or REVISION_REQUESTED, and the {@code version}
 * optimistic lock makes a concurrent edit lose rather than silently overwrite.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CTR01EditGuardTest {

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
    ContractService contracts;

    @Test
    void editIsRejectedOutsideDraftAndRevisionRequested() {
        // Every non-editable status must refuse update() with a 409-mapped ConflictException,
        // not merely fail to persist.
        throw new UnsupportedOperationException("session-3 Phase B — CTR-01 edit guard");
    }

    @Test
    void staleVersionLosesTheOptimisticLock() {
        // Two reads of the same DRAFT, two writes: the second must fail on version, not clobber.
        throw new UnsupportedOperationException("session-3 Phase B — CTR-01 optimistic lock");
    }

    @Test
    void editingRevisionRequestedFlipsItBackToDraft() {
        // registry §9: REVISION_REQUESTED -> DRAFT happens BY editing; there is no separate action.
        // The flip and the field update share one transaction and write one status_history row.
        throw new UnsupportedOperationException("session-3 Phase B — REVISION_REQUESTED -> DRAFT");
    }

}
