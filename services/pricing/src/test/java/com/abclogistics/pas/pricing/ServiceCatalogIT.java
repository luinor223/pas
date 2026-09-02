package com.abclogistics.pas.pricing;

import com.abclogistics.pas.pricing.domain.ServiceItem;
import com.abclogistics.pas.pricing.repository.ServiceItemRepository;
import com.abclogistics.pas.pricing.service.ServiceCatalogService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Boots the service against real Postgres + Redis: catalog seeds load, code is unique. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceCatalogIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas").withUsername("pas").withPassword("pas");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.grpc.server.port", () -> "0");
        r.add("outbox.relay.enabled", () -> "false");
    }

    @Autowired ServiceItemRepository items;
    @Autowired ServiceCatalogService catalog;

    @Test
    void seedsLoadedAndActive() {
        assertThat(items.count()).isEqualTo(6);
        assertThat(catalog.list(true)).hasSize(6);
        assertThat(catalog.getByCode("LIFT_ON_OFF").getUnit()).isEqualTo("TEU");
        assertThat(catalog.getByCode("WEIGHING_VGM").getName()).isEqualTo("Weighing (VGM)");
    }

    @Test
    void codeIsUnique() {
        assertThatThrownBy(() -> items.saveAndFlush(new ServiceItem("LIFT_ON_OFF", "dup", "TEU")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
