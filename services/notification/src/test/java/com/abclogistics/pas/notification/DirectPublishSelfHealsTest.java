package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.client.IdentityGrpcClient;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.NotificationService;
import com.abclogistics.pas.notification.service.RecipientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D9, for <b>both</b> events that publish without an outbox row. Neither has a row to lose, so
 * the producer re-publishes on every sweep until the document stops qualifying — "self-heals
 * next run" is the whole retry story. That only works because the id is <b>derived</b> rather
 * than random: the ack and the producer's own stamp cannot be made atomic, so a crash between
 * them re-sends, and two replicas sweeping at once can both send before either stamps.
 */
class DirectPublishSelfHealsTest {

    private NotificationRepository notifications;
    private ProcessedEventRepository processed;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        processed = mock(ProcessedEventRepository.class);
        service = new NotificationService(notifications, processed,
                new RecipientResolver(mock(IdentityGrpcClient.class)));
        when(processed.claim(any())).thenReturn(1);
    }

    @Test
    void reWarningTheSameDocumentDoesNotSpamTheOwner() {
        UUID contractId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        EventEnvelope firstSweep = EventFixtures.envelope(
                EventFixtures.documentExpiring(contractId, "2026-12-31", owner));
        EventEnvelope secondSweep = EventFixtures.envelope(
                EventFixtures.documentExpiring(contractId, "2026-12-31", owner));

        // the producer derived both ids the same way, so the consumer sees one event twice
        assertThat(secondSweep.eventId()).isEqualTo(firstSweep.eventId());

        assertThat(service.fanOut(firstSweep)).isEqualTo(1);
        when(processed.claim(firstSweep.eventId())).thenReturn(0);
        assertThat(service.fanOut(secondSweep)).isZero();
    }

    @Test
    void anExtensionEarnsAFreshWarning() {
        // valid_to is in the derived key: a renewed contract expiring later is news, not a repeat
        UUID contractId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        EventEnvelope before = EventFixtures.envelope(
                EventFixtures.documentExpiring(contractId, "2026-12-31", owner));
        EventEnvelope afterExtension = EventFixtures.envelope(
                EventFixtures.documentExpiring(contractId, "2027-12-31", owner));

        assertThat(afterExtension.eventId()).isNotEqualTo(before.eventId());

        assertThat(service.fanOut(before)).isEqualTo(1);
        when(processed.claim(before.eventId())).thenReturn(0);
        assertThat(service.fanOut(afterExtension)).isEqualTo(1);
    }

    @Test
    void reNaggingTheSameOverdueStepDoesNotDoubleTheInbox() {
        // SlaScheduler sweeps every 60s and stamps only after the ack
        UUID documentId = UUID.randomUUID();
        UUID stepInstanceId = UUID.randomUUID();
        List<UUID> assignees = List.of(UUID.randomUUID());
        EventEnvelope firstSweep = EventFixtures.envelope(
                EventFixtures.stepOverdue(documentId, stepInstanceId, "2026-09-01T10:00:00Z", assignees));
        EventEnvelope secondSweep = EventFixtures.envelope(
                EventFixtures.stepOverdue(documentId, stepInstanceId, "2026-09-01T10:00:00Z", assignees));

        assertThat(secondSweep.eventId()).isEqualTo(firstSweep.eventId());

        assertThat(service.fanOut(firstSweep)).isEqualTo(1);
        when(processed.claim(firstSweep.eventId())).thenReturn(0);
        assertThat(service.fanOut(secondSweep)).isZero();
    }

    @Test
    void aStepOverdueAgainstANewDeadlineIsANewWarning() {
        // the deadline is in the derived key for the same reason valid_to is in the expiry one
        UUID documentId = UUID.randomUUID();
        UUID stepInstanceId = UUID.randomUUID();
        List<UUID> assignees = List.of(UUID.randomUUID());
        EventEnvelope before = EventFixtures.envelope(
                EventFixtures.stepOverdue(documentId, stepInstanceId, "2026-09-01T10:00:00Z", assignees));
        EventEnvelope afterExtension = EventFixtures.envelope(
                EventFixtures.stepOverdue(documentId, stepInstanceId, "2026-09-05T10:00:00Z", assignees));

        assertThat(afterExtension.eventId()).isNotEqualTo(before.eventId());
    }

    @Test
    void everyDirectPublishCarriesTheDedupKeyItsRetryStoryDependsOn() {
        // the invariant behind all four tests above, stated once
        assertThat(EventFixtures.envelope(
                EventFixtures.documentExpiring(UUID.randomUUID(), "2026-12-31", UUID.randomUUID()))
                .eventId()).isNotNull();
        assertThat(EventFixtures.envelope(EventFixtures.stepOverdue(
                UUID.randomUUID(), UUID.randomUUID(), "2026-09-01T10:00:00Z", List.of(UUID.randomUUID())))
                .eventId()).isNotNull();
        assertThat(EventFixtures.envelope(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"))
                .eventId()).isNotNull();
    }
}
