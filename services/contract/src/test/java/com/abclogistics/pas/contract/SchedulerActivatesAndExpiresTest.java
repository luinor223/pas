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
import com.abclogistics.pas.contract.scheduler.ContractStatusScheduler;

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
    }

    @Autowired
    ContractStatusScheduler scheduler;

    @Test
    void approvedBecomesActiveOnItsEffectiveDate() {
        throw new UnsupportedOperationException("session-3 Phase B — CTR-05 activation");
    }

    @Test
    void activationIgnoresSigningProgress() {
        // D14e: APPROVED -> ACTIVE fires on schedule whether or not a signing session exists,
        // is in progress, or failed. contract-service has no dependency on esign-service.
        throw new UnsupportedOperationException("session-3 Phase B — D14e independence");
    }

    @Test
    void activeBecomesExpiredAfterItsEndDate() {
        throw new UnsupportedOperationException("session-3 Phase B — expiry sweep");
    }

    @Test
    void expiringWarningIsPublishedDirectlyWithNoOutboxRow() {
        // D9: outbox row count must be unchanged after the warning sweep.
        throw new UnsupportedOperationException("session-3 Phase B — D9 direct publish");
    }

    @Test
    void sweepsAreIdempotentAcrossRuns() {
        // Self-healing: running twice must not double-write history rows or re-warn.
        throw new UnsupportedOperationException("session-3 Phase B — sweep idempotence");
    }

}
