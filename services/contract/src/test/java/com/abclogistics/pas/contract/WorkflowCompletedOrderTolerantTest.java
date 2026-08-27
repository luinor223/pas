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
import com.abclogistics.pas.contract.listener.WorkflowEventListener;

/**
 * registry §9 footnote ¹ — order tolerance.
 *
 * <p>Kafka orders sends, not commits, so {@code workflow.completed} can arrive while the document
 * is still SUBMITTED (its {@code instance_started} delayed in the outbox past the whole approval).
 * The skipped SUBMITTED → UNDER_REVIEW edge is applied first, then the outcome, in one
 * transaction, one status_history row each. Rejecting the out-of-order event would wedge the
 * document permanently.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class WorkflowCompletedOrderTolerantTest {

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
    WorkflowEventListener listener;

    @Test
    void completedArrivingWhileSubmittedAppliesBothEdges() {
        // Final status APPROVED, and TWO history rows: SUBMITTED->UNDER_REVIEW then
        // UNDER_REVIEW->APPROVED. Collapsing them into one row loses the audit trail D17 exists for.
        throw new UnsupportedOperationException("session-3 Phase B — order-tolerant apply");
    }

    @Test
    void bothEventsAreIdempotentInEitherOrder() {
        // instance_started arriving late, after completed already applied its edge, must be a
        // no-op via processed_event -- not a second UNDER_REVIEW row and not an error.
        throw new UnsupportedOperationException("session-3 Phase B — dedup both orders");
    }

    @Test
    void redeliveryAfterOffsetLossIsANoOp() {
        // Offsets commit after processing, so a mid-batch death re-reads applied records.
        throw new UnsupportedOperationException("session-3 Phase B — processed_event dedup");
    }

}
