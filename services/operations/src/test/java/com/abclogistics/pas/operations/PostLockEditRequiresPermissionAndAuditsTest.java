package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
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
import org.springframework.security.access.AccessDeniedException;
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
class PostLockEditRequiresPermissionAndAuditsTest {

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
    @Autowired OutboxRepository outbox;

    private final String periodCode = "2026-09";
    private UUID contractId = UUID.randomUUID();

    @BeforeEach
    void setupPeriod() {
        setAuthWithoutEditLocked();
        // clean create if not exists
        try {
            periodService.create(periodCode);
        } catch (Exception ignored) {}
    }

    private void setAuthWithoutEditLocked() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "ops", "Ops Officer", "OPERATIONS", List.of("OPS_OFFICER"));
        var authorities = List.of(
                new SimpleGrantedAuthority("volume:read"),
                new SimpleGrantedAuthority("volume:write"),
                new SimpleGrantedAuthority("volume:lock_period")
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    private void setAuthWithEditLocked() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "special", "Special User", "OPERATIONS", List.of("OPS_OFFICER"));
        var authorities = List.of(
                new SimpleGrantedAuthority("volume:read"),
                new SimpleGrantedAuthority("volume:write"),
                new SimpleGrantedAuthority("volume:lock_period"),
                new SimpleGrantedAuthority("volume:edit_locked")
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    @Test
    void postLockEditRequiresSpecialPermissionAndIsAudited() {
        setAuthWithoutEditLocked();
        // create volume while OPEN — should succeed
        VolumeResponse vol = volumeService.create(contractId, periodCode, "CONT_LIFT", new BigDecimal("10.500"), "initial");
        assertThat(vol.recordNo()).startsWith("VOL-2026-");
        assertThat(vol.quantity()).isEqualByComparingTo("10.500");
        assertThat(vol.serviceName()).isNotBlank();
        assertThat(vol.unit()).isEqualTo("TEU");

        long outboxAfterCreate = outbox.count();
        assertThat(outboxAfterCreate).isGreaterThan(0);
        // verify audit for create exists
        boolean hasCreateAudit = outbox.findAll().stream().anyMatch(e -> e.getEventType().equals("audit.recorded") && e.getPayload().contains("volume.created"));
        assertThat(hasCreateAudit).isTrue();

        // lock period
        periodService.lock(periodCode);

        // try edit without edit_locked -> 403
        assertThatThrownBy(() -> volumeService.update(vol.id(), new BigDecimal("20.000"), "adjust"))
                .isInstanceOf(AccessDeniedException.class);

        // verify no audit for failed edit
        long outboxAfterFailed = outbox.count();
        // count should not have increased due to failed guard (no audit)
        assertThat(outboxAfterFailed).isEqualTo(outboxAfterCreate + 1); // +1 for period.locked audit

        // now with special permission -> should succeed and audit
        setAuthWithEditLocked();
        VolumeResponse updated = volumeService.update(vol.id(), new BigDecimal("20.000"), "adjust after lock");
        assertThat(updated.quantity()).isEqualByComparingTo("20.000");

        long outboxAfterSuccess = outbox.count();
        assertThat(outboxAfterSuccess).isGreaterThan(outboxAfterFailed);
        List<OutboxEvent> audits = outbox.findAll().stream().filter(e -> e.getEventType().equals("audit.recorded")).toList();
        boolean hasUpdateAudit = audits.stream().anyMatch(e -> e.getPayload().contains("volume.updated"));
        assertThat(hasUpdateAudit).isTrue();
        // last audit should contain periodLocked flag
        OutboxEvent last = audits.get(audits.size() - 1);
        assertThat(last.getPayload()).contains("volume.updated");
    }

    @Test
    void openPeriodEditAllowedWithoutSpecialPermission() {
        String pc = "2026-10";
        setAuthWithoutEditLocked();
        try { periodService.create(pc); } catch (Exception ignored) {}
        // create vol
        VolumeResponse vol = volumeService.create(UUID.randomUUID(), pc, "STORAGE", new BigDecimal("5"), null);
        // edit while OPEN should be allowed even without edit_locked
        VolumeResponse updated = volumeService.update(vol.id(), new BigDecimal("6"), null);
        assertThat(updated.quantity()).isEqualByComparingTo("6");
    }
}
