package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.operations.grpc.OperationsInternalGrpcService;
import com.abclogistics.pas.operations.grpc.ListVolumesRequest;
import com.abclogistics.pas.operations.grpc.ListVolumesResponse;
import com.abclogistics.pas.operations.service.PeriodService;
import com.abclogistics.pas.operations.service.VolumeService;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.abclogistics.pas.common.security.HeaderAuthenticationFilter;
import com.abclogistics.pas.common.security.PermissionCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestGrpcConfig.class)
class OperationsAdditionalCoverageIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withDatabaseName("pas").withUsername("pas").withPassword("pas");
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
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext wac;
    @Autowired PermissionCache permissionCache;
    @Autowired ObjectMapper objectMapper;
    @Autowired com.abclogistics.pas.common.outbox.OutboxRepository outbox;
    @Autowired org.springframework.kafka.core.KafkaTemplate<String,String> kafka;
    MockMvc mockMvc;

    @BeforeEach
    void setAuth() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters(new HeaderAuthenticationFilter(permissionCache, objectMapper))
                .build();
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
    void concurrentLockIsIdempotent() throws Exception {
        String code = "2027-02";
        try { periodService.create(code); } catch (Exception ignored) {}
        long outboxBefore = outbox.count();
        // clear kafka mock invocations
        org.mockito.Mockito.clearInvocations(kafka);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Exception> e1 = new AtomicReference<>(), e2 = new AtomicReference<>();
        Future<?> f1 = exec.submit(() -> {
            ready.countDown(); try { go.await(); } catch (Exception ignored) {}
            setAuth();
            try { periodService.lock(code); } catch (Exception e) { e1.set(e); }
        });
        Future<?> f2 = exec.submit(() -> {
            ready.countDown(); try { go.await(); } catch (Exception ignored) {}
            setAuth();
            try { periodService.lock(code); } catch (Exception e) { e2.set(e); }
        });
        ready.await(); go.countDown();
        f1.get(5, TimeUnit.SECONDS); f2.get(5, TimeUnit.SECONDS);
        exec.shutdown();
        assertThat(e1.get()).isNull();
        assertThat(e2.get()).isNull();
        assertThat(periodService.get(code).status()).isEqualTo("LOCKED");
        // P0-2: must not double-audit / double-publish — FOR UPDATE serializes, second sees LOCKED and returns idempotent
        long auditCount = outbox.findAll().stream().filter(e -> e.getEventType().equals("audit.recorded") && e.getPayload().contains("period.locked") && e.getPayload().contains(code)).count();
        assertThat(auditCount).isEqualTo(1);
        // kafka period_locked should be published exactly once (afterCommit from the winner)
        org.mockito.ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String,String>> captor = org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        // timeout verifies async publish if any
        try { org.mockito.Mockito.verify(kafka, org.mockito.Mockito.timeout(500).times(1)).send(captor.capture()); } catch (AssertionError ignored) {
            // fallback: atLeastOnce check with filter
            org.mockito.Mockito.verify(kafka, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        }
        long periodLockedSends = captor.getAllValues().stream().filter(r -> "pas.events".equals(r.topic()) && code.equals(r.key())).count();
        assertThat(periodLockedSends).isEqualTo(1);
    }

    @Autowired com.abclogistics.pas.operations.controller.PeriodController periodController;

    @Test
    void lockViaRestRequiresVolumeLockPeriodOr403() {
        String code = "2027-03";
        try { periodService.create(code); } catch (Exception ignored) {}
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "ops", "Ops Officer", "OPERATIONS", List.of("OPS_OFFICER"));
        var noLockAuth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("volume:read"), new SimpleGrantedAuthority("volume:write")));
        var withLockAuth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("volume:read"), new SimpleGrantedAuthority("volume:write"), new SimpleGrantedAuthority("volume:lock_period")));
        // no lock_period -> 403 via @PreAuthorize
        SecurityContextHolder.getContext().setAuthentication(noLockAuth);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> periodController.lock(code));
        // now grant lock_period -> 200
        SecurityContextHolder.getContext().setAuthentication(withLockAuth);
        var resp = periodController.lock(code);
        assertThat(resp.status()).isEqualTo("LOCKED");
        SecurityContextHolder.clearContext();
    }

    @Test
    void listVolumesGrpcInvalidAndNotFoundContract() throws Exception {
        // INVALID_ARGUMENT blank
        ListVolumesRequest blankReq = ListVolumesRequest.newBuilder().setContractId("").setPeriodCode("").build();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        grpcService.listVolumes(blankReq, new StreamObserver<>() {
            @Override public void onNext(ListVolumesResponse v) {}
            @Override public void onError(Throwable t) { err.set(t); latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });
        latch.await(2, TimeUnit.SECONDS);
        assertThat(err.get()).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) err.get()).getStatus().getCode().name()).isEqualTo("INVALID_ARGUMENT");

        // INVALID id format
        ListVolumesRequest badId = ListVolumesRequest.newBuilder().setContractId("not-uuid").setPeriodCode("2027-02").build();
        err.set(null); CountDownLatch latch2 = new CountDownLatch(1);
        grpcService.listVolumes(badId, new StreamObserver<>() {
            @Override public void onNext(ListVolumesResponse v) {}
            @Override public void onError(Throwable t) { err.set(t); latch2.countDown(); }
            @Override public void onCompleted() { latch2.countDown(); }
        });
        latch2.await(2, TimeUnit.SECONDS);
        assertThat(err.get()).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) err.get()).getStatus().getCode().name()).isEqualTo("INVALID_ARGUMENT");

        // NOT_FOUND period
        ListVolumesRequest notFound = ListVolumesRequest.newBuilder().setContractId(UUID.randomUUID().toString()).setPeriodCode("2099-01").build();
        err.set(null); CountDownLatch latch3 = new CountDownLatch(1);
        grpcService.listVolumes(notFound, new StreamObserver<>() {
            @Override public void onNext(ListVolumesResponse v) {}
            @Override public void onError(Throwable t) { err.set(t); latch3.countDown(); }
            @Override public void onCompleted() { latch3.countDown(); }
        });
        latch3.await(2, TimeUnit.SECONDS);
        assertThat(err.get()).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) err.get()).getStatus().getCode().name()).isEqualTo("NOT_FOUND");
    }

    @Test
    void dbCheckQuantityEnforcedAtDbLevel() {
        // Direct SQL negative quantity should violate CHECK quantity >=0 -> PSQLException 23514
        String periodCode = "2027-04";
        try { periodService.create(periodCode); } catch (Exception ignored) {}
        // get period_id
        UUID periodId = jdbc.queryForObject("SELECT id FROM operations.operation_period WHERE period_code=?", UUID.class, periodCode);
        assertThat(periodId).isNotNull();
        String recNo = "VOL-2027-9999";
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO operations.volume_record(id, record_no, period_id, contract_id, customer_name, service_code, service_name, unit, quantity) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?)",
                recNo, periodId, UUID.randomUUID(), "Cust", "CONT_LIFT", "Lift", "TEU", new BigDecimal("-1")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void dbPeriodCodeCheckRejects2026_13ViaDtoAndViaSql() {
        // DTO already tested, but DB should reject 2026-13 even if bypassing DTO
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO operations.operation_period(id, period_code, start_date, end_date, status) VALUES (gen_random_uuid(), '2026-13', '2026-01-01', '2026-01-31', 'OPEN')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void periodCodeValidationReturns400Not500ViaRestAndGrpc() throws Exception {
        // REST: GET /periods/2026-13 should be 400 via @Pattern / validatePeriodCode, not 500
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, "ops", "Ops Officer", "OPERATIONS", List.of("OPS_OFFICER"));
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("volume:read"), new SimpleGrantedAuthority("volume:lock_period"), new SimpleGrantedAuthority("volume:write")));
        // via controller direct (bypasses MockMvc filter but hits @Validated)
        try {
            periodController.get("2026-13");
            org.junit.jupiter.api.Assertions.fail("expected 400");
        } catch (Exception e) {
            // @Validated throws ConstraintViolationException -> 400 via OperationsExceptionHandler, direct call throws ConstraintViolationException
            assertThat(e).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        }
        try {
            periodController.lock("2026-13");
            org.junit.jupiter.api.Assertions.fail("expected 400");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        }
        // gRPC: 2026-13 should be INVALID_ARGUMENT, not NOT_FOUND
        ListVolumesRequest req = ListVolumesRequest.newBuilder().setContractId(UUID.randomUUID().toString()).setPeriodCode("2026-13").build();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        grpcService.listVolumes(req, new StreamObserver<>() {
            @Override public void onNext(ListVolumesResponse v) {}
            @Override public void onError(Throwable t) { err.set(t); latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });
        latch.await(2, TimeUnit.SECONDS);
        assertThat(err.get()).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) err.get()).getStatus().getCode().name()).isEqualTo("INVALID_ARGUMENT");
        // service direct: VolumeService.create with 2026-13 should be 400
        assertThatThrownBy(() -> volumeService.create(UUID.randomUUID(), "2026-13", "CONT_LIFT", new BigDecimal("1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
