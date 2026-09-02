package com.abclogistics.pas.notification;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.domain.NotificationCategory;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.IdentityGrpcClient;
import com.abclogistics.pas.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** D6 against a real database, because the mocked half cannot see any of this. */
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
    @MockitoSpyBean NotificationRepository notifications;
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
        // the claim is the serialization point: the loser blocks on the primary key, then sees
        // zero rows inserted and writes nothing. Check-then-act let both pass the read.
        EventEnvelope event = EventFixtures.envelope(EventFixtures.stepAssigned(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            outcomes.add(pool.submit(() -> {
                startTogether.await();
                return service.fanOut(event);
            }));
        }
        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // exactly one winner, and neither had to fail to get there
        assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get()))
                .containsExactlyInAnyOrder(2, 0);
        assertThat(notifications.findByEventId(event.eventId())).hasSize(2);
        assertThat(processed.existsById(event.eventId())).isTrue();
    }

    @Test
    void aFailureAfterSomeRowsAreWrittenRollsThemBack() {
        // the failure must land *after* the first insert, or this passes without @Transactional
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        EventEnvelope event = EventFixtures.envelope(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(first, second)));
        failOnTheRowFor(second);

        assertThatThrownBy(() -> service.fanOut(event))
                .isInstanceOf(CannotAcquireLockException.class);

        reset(notifications);
        assertThat(notifications.findByEventId(event.eventId())).isEmpty();
        // the claim is rolled back with them, so the redelivery is not mistaken for a duplicate
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
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        EventEnvelope event = EventFixtures.envelope(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(first, second)));
        failOnTheRowFor(second);
        assertThatThrownBy(() -> service.fanOut(event)).isInstanceOf(CannotAcquireLockException.class);

        reset(notifications);

        assertThat(service.fanOut(event)).isEqualTo(2);
        assertThat(notifications.findByEventId(event.eventId())).hasSize(2);
    }

    @Test
    void aClaimedEventWhoseTextCannotBeRenderedIsRolledBackAndReplayable() {
        // the claim now goes in *before* the render, so only the rollback keeps the producer's
        // own correction alive — this is the path the mocked tests cannot see
        ConsumerRecord<String, String> good = EventFixtures.stepAssigned(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID()));
        EventEnvelope broken = EventFixtures.envelope(
                EventFixtures.withPayloadField(good, "step_name", null));

        assertThatThrownBy(() -> service.fanOut(broken))
                .isInstanceOf(MalformedEventException.class);
        assertThat(processed.existsById(broken.eventId())).isFalse();

        EventEnvelope corrected = EventFixtures.envelope(good);
        assertThat(corrected.eventId()).isEqualTo(broken.eventId());

        assertThat(service.fanOut(corrected)).isEqualTo(2);
        assertThat(notifications.findByEventId(corrected.eventId())).hasSize(2);
        assertThat(processed.existsById(corrected.eventId())).isTrue();
    }

    /** The spy passes every other row through, so the first recipient is really inserted. */
    private void failOnTheRowFor(UUID recipient) {
        doThrow(new CannotAcquireLockException("recipient row failed")).when(notifications)
                .save(argThat(n -> n != null && recipient.equals(n.getRecipientUserId())));
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
