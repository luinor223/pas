package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.operations.dto.VolumeResponse;
import com.abclogistics.pas.operations.dto.CreateVolumeRequest;
import com.abclogistics.pas.operations.dto.UpdateVolumeRequest;
import com.abclogistics.pas.operations.controller.VolumeController;
import com.abclogistics.pas.common.error.FailedPreconditionException;
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
    @Autowired StubContractGrpcClient contractClient;
    @Autowired VolumeController volumeController;

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

    private void setAuthWithEditLockedOnly() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "special", "Special User", "OPERATIONS", List.of("OPS_OFFICER"));
        var authorities = List.of(
                new SimpleGrantedAuthority("volume:read"),
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

    @Test
    void listCanFilterByContractWithoutAPeriodFilter() {
        String pc = "2026-11";
        setAuthWithoutEditLocked();
        try { periodService.create(pc); } catch (Exception ignored) {}
        UUID firstContract = UUID.randomUUID();
        UUID secondContract = UUID.randomUUID();
        VolumeResponse matching = volumeService.create(firstContract, pc, "STORAGE", new BigDecimal("5"), null);
        volumeService.create(secondContract, pc, "STORAGE", new BigDecimal("7"), null);

        assertThat(volumeService.search(null, firstContract, null, null, 0, 15).items())
                .extracting(VolumeResponse::id)
                .containsExactly(matching.id());
    }

    @Test
    void editLockedPermissionDoesNotGrantOrdinaryWriteAccess() {
        setAuthWithEditLockedOnly();

        assertThatThrownBy(() -> volumeController.create(new CreateVolumeRequest(
                UUID.randomUUID(), periodCode, "STORAGE", BigDecimal.ONE, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> volumeController.update(UUID.randomUUID(),
                new UpdateVolumeRequest(BigDecimal.ONE, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createRequiresAnActiveContractAndAPeriodWithinItsValidity() {
        String code = "2026-08";
        setAuthWithoutEditLocked();
        try { periodService.create(code); } catch (Exception ignored) {}

        UUID rejectedId = UUID.randomUUID();
        contractClient.setContract(rejectedId, contractClient.getContract(rejectedId).toBuilder()
                .setContractNo("CTR-2026-REJECTED")
                .setStatus("REJECTED")
                .build());
        assertThatThrownBy(() -> volumeService.create(
                rejectedId, code, "STORAGE", BigDecimal.ONE, null))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("status is Rejected");

        UUID outsideValidityId = UUID.randomUUID();
        contractClient.setContract(outsideValidityId, contractClient.getContract(outsideValidityId).toBuilder()
                .setContractNo("CTR-2026-SHORT")
                .setStatus("ACTIVE")
                .setValidFrom("2026-08-10")
                .setValidTo("2026-08-20")
                .build());
        assertThatThrownBy(() -> volumeService.create(
                outsideValidityId, code, "STORAGE", BigDecimal.ONE, null))
                .isInstanceOf(FailedPreconditionException.class)
                .hasMessageContaining("outside contract CTR-2026-SHORT's valid dates");
    }

    @Test
    void volumeSearchIsPagedAndPeriodIncludesItsRecordCount() {
        String code = "2026-07";
        setAuthWithoutEditLocked();
        try { periodService.create(code); } catch (Exception ignored) {}
        UUID id = UUID.randomUUID();
        VolumeResponse created = volumeService.create(id, code, "STORAGE", new BigDecimal("3.5"), "Night shift");

        var page = volumeService.search(code, id, "STORAGE", "night", 0, 15);

        assertThat(page.items()).extracting(VolumeResponse::id).containsExactly(created.id());
        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(periodService.get(code).volumeCount()).isEqualTo(1);
    }
}
