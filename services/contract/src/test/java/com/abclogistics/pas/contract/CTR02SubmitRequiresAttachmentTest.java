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
 * CTR-02 — submit requires a valid customer, a valid validity window and at least one attachment.
 * Session 3 additionally requires vatRate and paymentTerm, which billing snapshots for PAY-03:
 * a null VAT silently treated as 0% is exactly the invoice drift the system exists to prevent.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CTR02SubmitRequiresAttachmentTest {

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
    void submitWithoutAttachmentIsRejected() {
        throw new UnsupportedOperationException("session-3 Phase B — CTR-02 attachment check");
    }

    @Test
    void submitWithSuspendedCustomerIsRejected() {
        throw new UnsupportedOperationException("session-3 Phase B — CTR-02 customer check");
    }

    @Test
    void submitWithoutVatRateOrPaymentTermIsRejected() {
        // Both are nullable in DRAFT and required at submit. A null vatRate must be refused,
        // never defaulted to zero.
        throw new UnsupportedOperationException("session-3 Phase B — CTR-02 billing fields");
    }

    @Test
    void vatRateOutsideZeroToHundredIsRejected() {
        // numeric(5,2) only bounds it to +/-999.99; the business range is a submit check.
        throw new UnsupportedOperationException("session-3 Phase B — CTR-02 vat range");
    }

    @Test
    void zeroVatRateIsAccepted() {
        // 0% is a deliberate, billable value and must not be conflated with "not stated".
        throw new UnsupportedOperationException("session-3 Phase B — CTR-02 zero vat");
    }

}
