package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.IdentityGrpcClient;
import com.abclogistics.pas.notification.service.NotificationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D6 against a real database, because the mocked half cannot see any of this.
 *
 * <p>{@link ProcessedEventDedupTest} stubs {@code existsById}, so it proves the service asks the
 * question — not that the answer is safe. Two things only Postgres can settle: that the
 * {@code processed_event} primary key rejects a duplicate the check raced past (a rebalance can
 * hand the same record to two consumers, and check-then-act has a window between them), and that
 * the notification rows and the {@code processed_event} row commit <em>together</em> — a partial
 * commit leaves the event unprocessed with its rows already written, so the redelivery doubles
 * the inbox, which is the exact failure D6 exists to prevent.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class NotificationDedupAtomicityIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_notification").withUsername("pas").withPassword("pas");

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
        registry.add("notification.kafka.listener-enabled", () -> "false");
    }

    @Autowired NotificationService service;
    @Autowired NotificationRepository notifications;
    @Autowired ProcessedEventRepository processed;
    @MockitoBean IdentityGrpcClient identity;

    @Test
    void aRedeliveryLeavesExactlyOneSetOfRows() {
        EventEnvelope event = EventFixtures.envelope(EventFixtures.stepAssigned(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())));

        assertThat(service.fanOut(event)).isEqualTo(2);
        assertThat(service.fanOut(event)).isZero();

        assertThat(notifications.findByEventId(event.eventId())).hasSize(2);
        assertThat(processed.existsById(event.eventId())).isTrue();
    }

    @Test
    void twoConsumersProcessingTheSameRecordAtOnceWriteOneSetBetweenThem() throws Exception {
        // the check-then-act window: both see existsById == false, both fan out, and only the
        // processed_event primary key can stop the second one committing
        EventEnvelope event = EventFixtures.envelope(EventFixtures.stepAssigned(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                startTogether.await();
                try {
                    service.fanOut(event);
                    succeeded.incrementAndGet();
                } catch (RuntimeException duplicate) {
                    // the PK did its job; the redelivery is retried and then dedups cleanly
                }
                return null;
            });
        }
        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(notifications.findByEventId(event.eventId())).hasSize(2);
    }

    @Test
    void aFailurePartWayThroughTheFanOutWritesNothingAtAll() {
        // three recipients, and identity is what fails: the transaction must roll back the rows
        // already written, or the redelivery adds a second copy of them
        when(identity.listUsersByRole("ACCOUNTANT"))
                .thenThrow(new IllegalStateException("identity unreachable"));
        EventEnvelope event = EventFixtures.envelope(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"));

        try {
            service.fanOut(event);
        } catch (RuntimeException expected) {
            // rethrown so the error handler can retry it — the point is what is left behind
        }

        assertThat(notifications.findByEventId(event.eventId())).isEmpty();
        assertThat(processed.existsById(event.eventId())).isFalse();
    }

    @Test
    void theRedeliveryAfterARollbackWritesExactlyOneCompleteSet() {
        UUID accountant = UUID.randomUUID();
        when(identity.listUsersByRole("ACCOUNTANT"))
                .thenThrow(new IllegalStateException("identity unreachable"));
        EventEnvelope event = EventFixtures.envelope(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"));

        try {
            service.fanOut(event);
        } catch (RuntimeException expected) {
            // first delivery fails
        }
        when(identity.listUsersByRole("ACCOUNTANT")).thenReturn(List.of(accountant));

        assertThat(service.fanOut(event)).isEqualTo(1);
        assertThat(notifications.findByEventId(event.eventId())).hasSize(1);
    }

    @Test
    void markAllReadTouchesNobodyElsesInbox() {
        UUID me = UUID.randomUUID();
        UUID them = UUID.randomUUID();
        service.fanOut(EventFixtures.envelope(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(me, them))));

        service.markAllRead(me);

        assertThat(service.inbox(me, true, null, PageRequest.of(0, 20)).items()).isEmpty();
        assertThat(service.inbox(them, true, null, PageRequest.of(0, 20)).items()).hasSize(1);
    }

    @Test
    void markAllReadKeepsAnAlreadyReadTimestamp() {
        // the read_at is null guard: the second sweep must not restamp what the first one read
        UUID me = UUID.randomUUID();
        service.fanOut(EventFixtures.envelope(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(me))));

        service.markAllRead(me);
        Instant firstReadAt = service.inbox(me, false, null, PageRequest.of(0, 20))
                .items().getFirst().readAt();
        service.markAllRead(me);

        assertThat(service.inbox(me, false, null, PageRequest.of(0, 20))
                .items().getFirst().readAt()).isEqualTo(firstReadAt);
    }

    @Test
    void theUnreadBadgeCountsTheWholeInboxWhileATabFiltersTheList() {
        // the badge/list split against real rows: two categories, one filtered view
        UUID me = UUID.randomUUID();
        service.fanOut(EventFixtures.envelope(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(me))));
        service.fanOut(EventFixtures.envelope(
                EventFixtures.esignCompleted(UUID.randomUUID(), me, "SIGNED")));

        var approvals = service.inbox(me, false, NotificationCategory.APPROVAL, PageRequest.of(0, 20));

        assertThat(approvals.items()).hasSize(1);
        assertThat(approvals.unreadCount()).isEqualTo(2);
    }
}
