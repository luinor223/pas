package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.operations.dto.VolumeResponse;
import com.abclogistics.pas.operations.service.PeriodService;
import com.abclogistics.pas.operations.service.VolumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestGrpcConfig.class)
class ServiceCodeValidatedAtEntryTest {

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
        r.add("outbox.relay.enabled", () -> "false");
        r.add("contract.grpc.host", () -> "localhost");
        r.add("contract.grpc.port", () -> "50052");
        r.add("pricing.grpc.host", () -> "localhost");
        r.add("pricing.grpc.port", () -> "50053");
    }

    @Autowired PeriodService periodService;
    @Autowired VolumeService volumeService;
    @Autowired StubPricingGrpcClient pricingStub;

    private final String periodCode = "2026-08";

    @BeforeEach
    void setAuthAndPeriod() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "ops", "Ops", "OPERATIONS", List.of("OPS_OFFICER"));
        var authorities = List.of(
                new SimpleGrantedAuthority("volume:read"),
                new SimpleGrantedAuthority("volume:write"),
                new SimpleGrantedAuthority("volume:lock_period"),
                new SimpleGrantedAuthority("volume:edit_locked")
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
        try { periodService.create(periodCode); } catch (Exception ignored) {}
    }

    @Test
    void invalidServiceCodeRejectedAtEntry() {
        UUID contractId = UUID.randomUUID();
        assertThatThrownBy(() -> volumeService.create(contractId, periodCode, "INVALID_XYZ", new BigDecimal("10"), null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service item not found");
    }

    @Test
    void validServiceCodeSnapshotsNameAndUnit() {
        UUID contractId = UUID.randomUUID();
        VolumeResponse resp = volumeService.create(contractId, periodCode, "CONT_LIFT", new BigDecimal("15.250"), "ok");
        assertThat(resp.serviceCode()).isEqualTo("CONT_LIFT");
        assertThat(resp.serviceName()).isEqualTo("Container lift on/off");
        assertThat(resp.unit()).isEqualTo("TEU");
        assertThat(resp.quantity()).isEqualByComparingTo("15.250");
        assertThat(resp.customerName()).isNotBlank();
        assertThat(resp.recordNo()).startsWith("VOL-2026-");
    }

    @Test
    void inactiveServiceCodeRejected() {
        // register inactive item
        pricingStub.setServiceItem("INACTIVE_SVC", com.abclogistics.pas.pricing.grpc.GetServiceItemResponse.newBuilder()
                .setCode("INACTIVE_SVC").setName("Inactive").setUnit("TEU").setIsActive(false).build());
        UUID contractId = UUID.randomUUID();
        assertThatThrownBy(() -> volumeService.create(contractId, periodCode, "INACTIVE_SVC", new BigDecimal("5"), null))
                .isInstanceOf(com.abclogistics.pas.common.error.FailedPreconditionException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void negativeQuantityRejected() {
        UUID contractId = UUID.randomUUID();
        assertThatThrownBy(() -> volumeService.create(contractId, periodCode, "CONT_LIFT", new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }
}
