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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** D6 against a real database, because the mocked half cannot see any of this. *
 * <p><b>Not yet exercised:</b> needs Phase B's {@code V1__} migration — {@code notification}
 * and {@code processed_event} do not exist yet, so this class fails at context startup.
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
    @MockitoSpyBean ProcessedEventRepository processed;
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
        // check-then-act: both see existsById == false, and only the PK stops the second commit
        EventEnvelope event = EventFixtures.envelope(EventFixtures.stepAssigned(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                startTogether.await();
                try {
                    service.fanOut(event);
                } catch (RuntimeException loser) {
                    // either outcome is correct: throw on the PK, or claim and write nothing
                }
                return null;
            });
        }
        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // the business outcome, whichever way the loser reported it
        assertThat(notifications.findByEventId(event.eventId())).hasSize(2);
        assertThat(processed.existsById(event.eventId())).isTrue();
    }

    @Test
    void aFailureAfterSomeRowsAreWrittenRollsThemBack() {
        // the failure must land *after* the inserts, or this passes without @Transactional
        EventEnvelope event = EventFixtures.envelope(EventFixtures.stepAssigned(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())));
        doThrow(new CannotAcquireLockException("processed_event write failed"))
                .when(processed).save(any());

        assertThatThrownBy(() -> service.fanOut(event))
                .isInstanceOf(CannotAcquireLockException.class);

        assertThat(notifications.findByEventId(event.eventId())).isEmpty();
        assertThat(processed.existsById(event.eventId())).isFalse();
    }

    @Test
    void aFailureResolvingRecipientsWritesNothingEither() {
        when(identity.listUsersByRole("ACCOUNTANT"))
                .thenThrow(new IllegalStateException("identity unreachable"));
        EventEnvelope event = EventFixtures.envelope(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"));

        assertThatThrownBy(() -> service.fanOut(event)).isInstanceOf(RuntimeException.class);

        assertThat(notifications.findByEventId(event.eventId())).isEmpty();
        assertThat(processed.existsById(event.eventId())).isFalse();
    }

    @Test
    void theRedeliveryAfterARollbackWritesExactlyOneCompleteSet() {
        EventEnvelope event = EventFixtures.envelope(EventFixtures.stepAssigned(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())));
        doThrow(new CannotAcquireLockException("processed_event write failed"))
                .when(processed).save(any());
        assertThatThrownBy(() -> service.fanOut(event)).isInstanceOf(CannotAcquireLockException.class);

        reset(processed);

        assertThat(service.fanOut(event)).isEqualTo(2);
        assertThat(notifications.findByEventId(event.eventId())).hasSize(2);
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
