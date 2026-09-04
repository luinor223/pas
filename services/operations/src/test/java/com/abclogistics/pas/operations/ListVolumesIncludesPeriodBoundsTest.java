package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.operations.controller.grpc.OperationsInternalGrpcService;
import com.abclogistics.pas.operations.grpc.ListVolumesRequest;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.operations.service.PeriodService;
import com.abclogistics.pas.operations.service.VolumeService;
import io.grpc.stub.StreamObserver;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestGrpcConfig.class)
class ListVolumesIncludesPeriodBoundsTest {

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
    @Autowired OperationsInternalGrpcService grpcService;
    @Autowired StubContractGrpcClient contractClient;

    @BeforeEach
    void setAuth() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "ops", "Ops Officer", "OPERATIONS", List.of("OPS_OFFICER"));
        var authorities = List.of(
                new SimpleGrantedAuthority("volume:read"),
                new SimpleGrantedAuthority("volume:write"),
                new SimpleGrantedAuthority("volume:lock_period"),
                new SimpleGrantedAuthority("volume:edit_locked")
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    @Test
    void listVolumesReturnsPeriodBoundsAndSnapshots() throws Exception {
        String periodCode = "2026-11";
        UUID contractId = UUID.randomUUID();
        try { periodService.create(periodCode); } catch (Exception ignored) {}

        // create 2 volumes for same contract+period
        var v1 = volumeService.create(contractId, periodCode, "CONT_LIFT", new BigDecimal("12.000"), null);
        var v2 = volumeService.create(contractId, periodCode, "STORAGE", new BigDecimal("7.500"), "note");

        assertThat(v1.recordNo()).startsWith("VOL-2026-");
        assertThat(v2.recordNo()).startsWith("VOL-2026-");
        assertThat(v1.serviceName()).isNotBlank();
        assertThat(v1.unit()).isNotBlank();

        // call gRPC ListVolumes
        ListVolumesRequest req = ListVolumesRequest.newBuilder()
                .setContractId(contractId.toString())
                .setPeriodCode(periodCode)
                .build();

        AtomicReference<ListVolumesResponse> respRef = new AtomicReference<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        grpcService.listVolumes(req, new StreamObserver<>() {
            @Override public void onNext(ListVolumesResponse value) { respRef.set(value); }
            @Override public void onError(Throwable t) { errRef.set(t); latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errRef.get()).isNull();
        ListVolumesResponse resp = respRef.get();
        assertThat(resp).isNotNull();
        assertThat(resp.getPeriodState()).isEqualTo("OPEN");
        assertThat(resp.getPeriodStart()).isEqualTo("2026-11-01");
        assertThat(resp.getPeriodEnd()).isEqualTo("2026-11-30");
        assertThat(resp.getVolumesCount()).isEqualTo(2);
        assertThat(resp.getVolumesList()).extracting(v -> v.getServiceCode()).containsExactlyInAnyOrder("CONT_LIFT", "STORAGE");
        // verify quantity and snapshots are returned
        assertThat(resp.getVolumesList()).anyMatch(v -> v.getQuantity() == 12.0 && v.getUnit().equals("TEU"));
    }

    @Test
    void listVolumesEmptyPeriodStillReturnsBounds() throws Exception {
        String periodCode = "2026-12";
        UUID contractId = UUID.randomUUID();
        try { periodService.create(periodCode); } catch (Exception ignored) {}

        ListVolumesRequest req = ListVolumesRequest.newBuilder()
                .setContractId(contractId.toString())
                .setPeriodCode(periodCode)
                .build();

        AtomicReference<ListVolumesResponse> respRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        grpcService.listVolumes(req, new StreamObserver<>() {
            @Override public void onNext(ListVolumesResponse value) { respRef.set(value); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });
        latch.await(5, TimeUnit.SECONDS);
        ListVolumesResponse resp = respRef.get();
        assertThat(resp).isNotNull();
        assertThat(resp.getPeriodStart()).isEqualTo("2026-12-01");
        assertThat(resp.getPeriodEnd()).isEqualTo("2026-12-31");
        assertThat(resp.getVolumesCount()).isZero();
        assertThat(resp.getPeriodState()).isEqualTo("OPEN");
    }

    @Test
    void listVolumesAfterLockShowsLockedState() throws Exception {
        String periodCode = "2027-01";
        UUID contractId = UUID.randomUUID();
        try { periodService.create(periodCode); } catch (Exception ignored) {}
        contractClient.setContract(contractId, contractClient.getContract(contractId).toBuilder()
                .setValidTo("2027-12-31")
                .build());
        volumeService.create(contractId, periodCode, "CONT_LIFT", new BigDecimal("1"), null);
        periodService.lock(periodCode);

        ListVolumesRequest req = ListVolumesRequest.newBuilder()
                .setContractId(contractId.toString())
                .setPeriodCode(periodCode)
                .build();

        AtomicReference<ListVolumesResponse> respRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        grpcService.listVolumes(req, new StreamObserver<>() {
            @Override public void onNext(ListVolumesResponse value) { respRef.set(value); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(respRef.get().getPeriodState()).isEqualTo("LOCKED");
    }
}
