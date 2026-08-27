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
 * D10 / D14e — contract-service owns the send-for-signing action (registry §6 third outbox use),
 * and it changes NO document status. Requirement 5.5 forbids mixing approval state and signing
 * state; the frontend composes the two for display.
 *
 * <p>The outbox is required rather than a synchronous call: APR-07 wants the user's send action to
 * survive esign-service being down, and a synchronous call committed afterwards would leave a
 * session sending to the provider for a document still APPROVED, then deliver a callback §9 has
 * no transition for.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SendForSigningNoStatusChangeTest {

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
    void sendWritesAnOutboxRowAndLeavesStatusUntouched() {
        // Status stays APPROVED and NO status_history row is written -- this is not a transition.
        throw new UnsupportedOperationException("session-3 Phase B — D10 send action");
    }

    @Test
    void sendIsRejectedUnlessApproved() {
        // GetSigningPayload's guard is status = APPROVED (registry §5).
        throw new UnsupportedOperationException("session-3 Phase B — D10 approved guard");
    }

    @Test
    void sendRequiresEsignSendPermission() {
        throw new UnsupportedOperationException("session-3 Phase B — esign:send permission");
    }

    @Test
    void activationStillFiresWhileSigningIsPending() {
        // APPROVED -> ACTIVE is purely date-driven and must not wait on a signing session.
        throw new UnsupportedOperationException("session-3 Phase B — D14e independence");
    }

}
