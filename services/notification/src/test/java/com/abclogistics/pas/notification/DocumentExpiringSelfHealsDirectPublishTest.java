package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.repository.NotificationRepository;
import com.abclogistics.pas.notification.repository.ProcessedEventRepository;
import com.abclogistics.pas.notification.service.IdentityGrpcClient;
import com.abclogistics.pas.notification.service.NotificationService;
import com.abclogistics.pas.notification.service.RecipientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D9. {@code document.expiring} has no outbox row, so contract-service re-publishes it on every
 * sweep until the document stops qualifying — "self-heals next run" is the whole retry story.
 * That only works because the id is <b>derived</b> from {@code document_id + expires_on} rather
 * than random: a re-warn dedups, while an extension is a genuinely new warning.
 *
 * <p>This is the one consumed event whose idempotency depends on the producer's id derivation,
 * which is why it gets a test of its own rather than riding on {@link ProcessedEventDedupTest}.
 */
class DocumentExpiringSelfHealsDirectPublishTest {

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
    void reWarningTheSameDocumentDoesNotSpamTheOwner() {
        UUID contractId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        EventEnvelope firstSweep = EventFixtures.documentExpiring(contractId, "2026-12-31", owner);
        EventEnvelope secondSweep = EventFixtures.documentExpiring(contractId, "2026-12-31", owner);

        // the producer derived both ids the same way, so the consumer sees one event twice
        assertThat(secondSweep.eventId()).isEqualTo(firstSweep.eventId());

        assertThat(service.fanOut(firstSweep)).isEqualTo(1);
        when(processed.existsById(firstSweep.eventId())).thenReturn(true);
        assertThat(service.fanOut(secondSweep)).isZero();
    }

    @Test
    void anExtensionEarnsAFreshWarning() {
        // valid_to is in the derived key: a renewed contract expiring later is news, not a repeat
        UUID contractId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        EventEnvelope before = EventFixtures.documentExpiring(contractId, "2026-12-31", owner);
        EventEnvelope afterExtension = EventFixtures.documentExpiring(contractId, "2027-12-31", owner);

        assertThat(afterExtension.eventId()).isNotEqualTo(before.eventId());

        assertThat(service.fanOut(before)).isEqualTo(1);
        when(processed.existsById(before.eventId())).thenReturn(true);
        assertThat(service.fanOut(afterExtension)).isEqualTo(1);
    }

    @Test
    void theWarningGoesToTheDocumentOwnerAlone() {
        UUID owner = UUID.randomUUID();

        assertThat(service.fanOut(EventFixtures.documentExpiring(UUID.randomUUID(), "2026-12-31", owner)))
                .isEqualTo(1);
    }
}
