package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.IdentityGrpcClient;
import com.abclogistics.pas.notification.service.NotificationService;
import com.abclogistics.pas.notification.service.RecipientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D6. Kafka is at-least-once and the offset commits after processing, so the same record can
 * arrive twice — a duplicated inbox is the visible symptom users would report.
 */
class ProcessedEventDedupTest {

    private NotificationRepository notifications;
    private ProcessedEventRepository processed;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        processed = mock(ProcessedEventRepository.class);
        service = new NotificationService(notifications, processed,
                new RecipientResolver(mock(IdentityGrpcClient.class)));
    }

    @Test
    void aRedeliveredEventWritesNothingASecondTime() {
        EventEnvelope event = EventFixtures.stepAssigned(UUID.randomUUID(),
                List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(service.fanOut(event)).isEqualTo(2);

        when(processed.existsById(event.eventId())).thenReturn(true);

        assertThat(service.fanOut(event)).isZero();
        verify(notifications, times(2)).save(any());   // still only the first delivery's two rows
    }

    @Test
    void theDedupKeyIsTheEnvelopeEventId() {
        // not (recipient, event_type, document) — two genuinely different events about one document
        // must both land, and only the envelope id distinguishes redelivery from recurrence
        EventEnvelope event = EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID()));

        service.fanOut(event);

        verify(processed).existsById(event.eventId());
    }

    @Test
    void anAlreadyProcessedEventNeverReachesTheRecipientResolver() {
        // the dedup check is the first thing that happens: resolving would cost a gRPC hop
        IdentityGrpcClient identity = mock(IdentityGrpcClient.class);
        NotificationService svc = new NotificationService(notifications, processed,
                new RecipientResolver(identity));
        EventEnvelope event = EventFixtures.periodLocked("2026-08", "ACCOUNTANT");
        when(processed.existsById(event.eventId())).thenReturn(true);

        assertThat(svc.fanOut(event)).isZero();
        verify(identity, never()).listUsersByRole("ACCOUNTANT");
    }
}
