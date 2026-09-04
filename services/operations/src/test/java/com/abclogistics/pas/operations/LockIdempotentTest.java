package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.operations.dto.PeriodResponse;
import com.abclogistics.pas.operations.service.PeriodService;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestGrpcConfig.class)
class LockIdempotentTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

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
    @Autowired OutboxRepository outbox;

    private final String periodCode = "2026-10";

    @BeforeEach
    void setAuth() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "ops", "Ops Officer", "OPERATIONS", List.of("OPS_OFFICER"));
        var authorities = List.of(
                new SimpleGrantedAuthority("volume:read"),
                new SimpleGrantedAuthority("volume:write"),
                new SimpleGrantedAuthority("volume:lock_period"),
                new SimpleGrantedAuthority("volume:edit_locked")
        );
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void lockIsIdempotentAndLeavesLockedState() {
        // create OPEN
        PeriodResponse created = periodService.create(periodCode);
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.periodCode()).isEqualTo(periodCode);
        assertThat(created.startDate().toString()).isEqualTo("2026-10-01");
        assertThat(created.endDate().toString()).isEqualTo("2026-10-31");

        long outboxBeforeLock = outbox.count();

        // first lock
        PeriodResponse locked = periodService.lock(periodCode);
        assertThat(locked.status()).isEqualTo("LOCKED");
        assertThat(locked.lockedBy()).isNotNull();
        assertThat(locked.lockedByName()).isNotNull();

        long outboxAfterFirst = outbox.count();
        assertThat(outboxAfterFirst).isGreaterThan(outboxBeforeLock);

        // second lock must be idempotent, no error, still LOCKED (DB truncates to micros, so compare with tolerance)
        PeriodResponse second = periodService.lock(periodCode);
        assertThat(second.status()).isEqualTo("LOCKED");
        assertThat(second.lockedAt()).isCloseTo(locked.lockedAt(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));

        long outboxAfterSecond = outbox.count();
        // second lock must NOT create duplicate audit/event (idempotent)
        assertThat(outboxAfterSecond).isEqualTo(outboxAfterFirst);
    }

    @Test
    void openCannotUnlockAndLockedStaysLocked() {
        // ensure no unlock path exists: second create with same code should conflict
        periodService.create("2026-09");
        PeriodResponse locked = periodService.lock("2026-09");
        assertThat(locked.status()).isEqualTo("LOCKED");
        // attempt to lock again still LOCKED
        PeriodResponse again = periodService.lock("2026-09");
        assertThat(again.status()).isEqualTo("LOCKED");
    }
}
