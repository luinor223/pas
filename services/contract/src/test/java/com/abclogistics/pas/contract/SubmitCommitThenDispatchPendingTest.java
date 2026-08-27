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
 * D4 commit-then-dispatch (registry §9 footnote ¹).
 *
 * <p>Submit commits the local status change, its status_history row and a
 * {@code workflow.start_requested} outbox row in ONE transaction, then a relay retries
 * {@code StartInstance}. The reverse order is the bug this pins: a remote success followed by a
 * failed local commit orphans a live, assignee-notified workflow instance on a document still
 * DRAFT, and no retry can undo that.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SubmitCommitThenDispatchPendingTest {

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
    void submitCommitsLocallyBeforeAnyRemoteCall() {
        // After submit with the relay disabled: status SUBMITTED, one history row, one unpublished
        // outbox row -- and no workflow instance anywhere.
        throw new UnsupportedOperationException("session-3 Phase B — D4 local commit");
    }

    @Test
    void outboxRowCarriesAStableIdempotencyKey() {
        // The key is generated ONCE at submit and reused by every retry, so a lost ack cannot
        // produce a second instance.
        throw new UnsupportedOperationException("session-3 Phase B — D4 idempotency key");
    }

    @Test
    void pendingDispatchRendersAsInitializationPendingNotAnError() {
        // A document genuinely SUBMITTED with no instance yet is a normal state, not a failure:
        // GetInstanceByDocument NOT_FOUND must render INITIALIZATION_PENDING from local status
        // and must not be retried from the progress endpoint.
        throw new UnsupportedOperationException("session-3 Phase B — INITIALIZATION_PENDING");
    }

    @Test
    void validateStartableFailureBlocksTheCommit() {
        // The pre-check runs BEFORE the commit so an unconfigured document type fails fast (412)
        // instead of parking in the outbox forever.
        throw new UnsupportedOperationException("session-3 Phase B — ValidateStartable pre-check");
    }

}
