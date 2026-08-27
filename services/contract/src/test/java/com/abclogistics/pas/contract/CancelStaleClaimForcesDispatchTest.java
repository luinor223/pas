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
 * §M2 cancel-vs-dispatch handoff.
 *
 * <p>The relay's claim is a timestamp lease with no fencing token, so "claimed a while ago" does
 * not mean "the claimer is dead". Only a row with {@code claimed_at IS NULL} may be cancelled
 * directly; a stale claim is re-claimed and its dispatch forced to completion before
 * {@code CancelInstance} runs.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CancelStaleClaimForcesDispatchTest {

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
    void unclaimedRowIsCancelledAtomically() {
        // claimed_at IS NULL: the outbox row is cancelled in the same transaction as the document,
        // and StartInstance is never called.
        throw new UnsupportedOperationException("session-3 Phase B — M2 unclaimed cancel");
    }

    @Test
    void staleClaimIsForcedToCompletionNotCancelled() {
        // A stale claim must be resolved, never assumed dead: force the dispatch, then cancel the
        // instance it created. Cancelling the row instead would let StartInstance land afterwards.
        throw new UnsupportedOperationException("session-3 Phase B — M2 stale claim");
    }

    @Test
    void documentIsNeverCancelledOnAnInconclusiveRead() {
        // The document stays SUBMITTED/UNDER_REVIEW until one branch definitively resolves, so no
        // workflow instance can start after the document was already CANCELLED.
        throw new UnsupportedOperationException("session-3 Phase B — M2 inconclusive read");
    }

    @Test
    void cancelFailsOutrightIfAStepWasAlreadyActioned() {
        throw new UnsupportedOperationException("session-3 Phase B — M2 already-actioned");
    }

}
