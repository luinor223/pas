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
 * CTR-06 — an ACTIVE contract is never deleted; it is cancelled or expired, and the controlled
 * cancel needs {@code contract:cancel_active}.
 * CTR-07 — a terms change on an APPROVED or ACTIVE contract must go through an addendum.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CTR06And07GuardTest {

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
    ContractService contracts;

    @Test
    void activeContractHasNoDeletePath() {
        throw new UnsupportedOperationException("session-3 Phase B — CTR-06 no delete");
    }

    @Test
    void cancellingActiveRequiresTheDedicatedPermission() {
        // contract:write is not enough -- cancelling a live contract is its own permission.
        // Code checks permissions, never roles.
        throw new UnsupportedOperationException("session-3 Phase B — CTR-06 permission");
    }

    @Test
    void termsChangeOnApprovedOrActiveIsRefusedAndNamesTheAddendumRoute() {
        // The refusal must tell the user what to do instead, or they will retry the same edit.
        throw new UnsupportedOperationException("session-3 Phase B — CTR-07 guard");
    }

}
