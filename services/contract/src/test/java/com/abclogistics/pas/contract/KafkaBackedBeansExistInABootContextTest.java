package com.abclogistics.pas.contract;

import com.abclogistics.pas.contract.outbox.ContractOutboxRelay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay and the consumer failure policy both require a {@code KafkaTemplate}, and nothing but a
 * real application context proves one exists: Boot 4 keeps Kafka's auto-configuration in its own
 * module, so {@code spring-kafka} alone puts the classes on the classpath and creates no beans at
 * all. {@link ContractOutboxRelayWiringTest} supplies a mock and therefore cannot see that gap.
 *
 * <p>The relay is left enabled here — the point is that Spring builds it.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class KafkaBackedBeansExistInABootContextTest {

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
        // never reached: no row is written here, so the relay polls an empty table
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("contract.kafka.listener-enabled", () -> "false");
    }

    @Autowired(required = false) KafkaTemplate<String, String> kafka;
    @Autowired(required = false) ContractOutboxRelay relay;
    @Autowired(required = false) DefaultErrorHandler errorHandler;

    @Test
    void kafkaAutoConfigurationProducesATemplate() {
        assertThat(kafka).isNotNull();
    }

    @Test
    void theRelayIsBuiltInARealContext() {
        // its absence would be silent: documents submit fine and nothing ever dispatches
        assertThat(relay).isNotNull();
    }

    @Test
    void theConsumerFailurePolicyIsBuiltInARealContext() {
        // registry §4's bounded retry and .DLT routing, likewise silent if missing
        assertThat(errorHandler).isNotNull();
    }
}
