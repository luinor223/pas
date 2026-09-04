package com.abclogistics.pas.billing;

import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registry §2 numbering comes from a per-type-per-year counter (V4
 * {@code billing.statement_no_counter}).
 */
@Tag("integration")
@Testcontainers
@Transactional
@SpringBootTest
class StatementNumberingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_billing").withUsername("pas").withPassword("pas");

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

    @Autowired PaymentStatementRepository statements;

    @Test
    void sequenceIncrementsWithinAYearAndRestartsPerYear() {
        assertThat(statements.nextStatementNoForYear(2026)).isEqualTo(1);
        assertThat(statements.nextStatementNoForYear(2026)).isEqualTo(2);
        assertThat(statements.nextStatementNoForYear(2027)).isEqualTo(1);
        assertThat(statements.nextStatementNoForYear(2026)).isEqualTo(3);
    }
}
