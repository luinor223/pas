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
import com.abclogistics.pas.contract.service.DocumentNumberService;

/**
 * registry §2 business keys. Numbers are server-generated and never client-supplied.
 *
 * <p>Customer codes carry NO year segment, so their sequence must run unbroken across years —
 * which is why {@code customer_counter} is a separate single-row table and not a
 * {@code (doc_type, year)} row in {@code document_counter}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class DocumentNumberingTest {

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
    DocumentNumberService numbers;

    @Test
    void contractNumbersAreCtrYearSeq() {
        throw new UnsupportedOperationException("session-3 Phase B — CTR numbering");
    }

    @Test
    void addendumNumbersAreAddYearSeq() {
        throw new UnsupportedOperationException("session-3 Phase B — ADD numbering");
    }

    @Test
    void documentSequenceRestartsEachYearPerType() {
        throw new UnsupportedOperationException("session-3 Phase B — per-year reset");
    }

    @Test
    void customerSequenceDoesNotRestartAcrossYears() {
        // A year-keyed customer counter would hand out CUS-1 again every January and collide on
        // customer.code's UNIQUE.
        throw new UnsupportedOperationException("session-3 Phase B — CUS continuity");
    }

    @Test
    void concurrentCreatesNeverShareASequence() {
        // The row lock is what prevents this; without it the race surfaces as a UNIQUE violation.
        throw new UnsupportedOperationException("session-3 Phase B — counter row lock");
    }

    @Test
    void clientSuppliedCodeIsIgnored() {
        throw new UnsupportedOperationException("session-3 Phase B — server-generated only");
    }

}
