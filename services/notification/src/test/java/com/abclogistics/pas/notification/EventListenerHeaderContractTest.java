package com.abclogistics.pas.notification;

import com.abclogistics.pas.common.outbox.EventRecords;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.listener.EventListener;
import com.abclogistics.pas.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The wire contract, which is where this service meets the other seven (registry §4).
 *
 * <p>The first spec pass had no test here at all: every test called {@code fanOut} with an envelope
 * it had built itself, so nothing checked what arrives on the topic. That is exactly where the
 * spec was wrong — it assumed a full envelope in the value while every producer sends the bare
 * payload with the envelope fields in headers.
 *
 * <p>Three rules: the dedup key comes from the {@code event_id} header, the {@code event_type}
 * header decides whether to parse at all, and nothing is swallowed.
 */
class EventListenerHeaderContractTest {

    private NotificationService notifications;
    private EventListener listener;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationService.class);
        listener = new EventListener(notifications, EventFixtures.MAPPER);
    }

    @Test
    void theDedupKeyIsReadFromTheHeaderNotTheValue() {
        // registry §4 change log: event_id is mirrored into a header precisely so no consumer needs
        // a payload-parsing path for it. The value carries the payload alone and has no event_id.
        ConsumerRecord<String, String> record =
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID()));
        assertThat(record.value()).doesNotContain("event_id");

        listener.onEvent(record);

        assertThat(captured().eventId())
                .isEqualTo(UUID.fromString(EventHeaders.of(record, EventHeaders.EVENT_ID)));
    }

    @Test
    void eventTypeAndDocumentTypeComeFromHeadersToo() {
        UUID documentId = UUID.randomUUID();

        listener.onEvent(EventFixtures.stepAssigned(documentId, List.of(UUID.randomUUID())));

        EventEnvelope envelope = captured();
        assertThat(envelope.eventType()).isEqualTo("workflow.step_assigned");
        assertThat(envelope.documentType()).isEqualTo("CONTRACT");
        // the partition key is the aggregate id, which for a document event is the document
        assertThat(envelope.documentId()).isEqualTo(documentId);
    }

    @Test
    void anEventThisServiceDoesNotConsumeIsSkippedWithoutDeserializing() {
        // pas.events carries every event in the system; most are not ours. The header filter is
        // what keeps that cheap — and the record must not reach fanOut at all.
        listener.onEvent(EventFixtures.instanceStarted(UUID.randomUUID()));

        verify(notifications, never()).fanOut(any());
    }

    @Test
    void aSkippedRecordIsNotAFailure() {
        // it must return normally: throwing would send another service's event to our DLT
        ConsumerRecord<String, String> notOurs = EventFixtures.instanceStarted(UUID.randomUUID());

        listener.onEvent(notOurs);   // no exception
    }

    @Test
    void aRecordWithNoEventTypeHeaderIsSkipped() {
        // nothing can be decided about it and it is not addressed to us; it is not ours to poison
        ConsumerRecord<String, String> headerless = EventRecords.withoutHeader(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                EventHeaders.EVENT_TYPE);

        listener.onEvent(headerless);

        verify(notifications, never()).fanOut(any());
    }

    @Test
    void anEventWeConsumeWithNoEventIdIsPermanentlyMalformed() {
        // this is the shape SlaScheduler published before the derived id landed. Without the
        // header there is no dedup key, so redelivering it can only duplicate the inbox — it is
        // DLT material, not retry material.
        ConsumerRecord<String, String> noId = EventRecords.withoutHeader(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                EventHeaders.EVENT_ID);

        assertThatThrownBy(() -> listener.onEvent(noId))
                .isInstanceOf(MalformedEventException.class);
        verify(notifications, never()).fanOut(any());
    }

    @Test
    void anEventIdThatIsNotAUuidIsPermanentlyMalformed() {
        ConsumerRecord<String, String> bad = EventRecords.consumed(EventRecords.directPublish(
                UUID.randomUUID(), "document.expiring", "CONTRACT", UUID.randomUUID(), "{}"));
        bad.headers().remove(EventHeaders.EVENT_ID);
        bad.headers().add(EventHeaders.EVENT_ID, "not-a-uuid".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> listener.onEvent(bad)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void aValueThatIsNotJsonIsPermanentlyMalformed() {
        ConsumerRecord<String, String> corrupt = EventRecords.withValue(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                "{\"assignee_ids\": [");

        assertThatThrownBy(() -> listener.onEvent(corrupt))
                .isInstanceOf(MalformedEventException.class);
        verify(notifications, never()).fanOut(any());
    }

    @Test
    void aTransientFailureIsRethrownRatherThanSwallowed() {
        // the DB being down must not commit the offset: swallowing loses the event silently, which
        // is the one outcome 4.9 cannot tolerate
        org.mockito.Mockito.when(notifications.fanOut(any()))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("db down"));

        assertThatThrownBy(() -> listener.onEvent(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID()))))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
    }

    @Test
    void aRoleAddressedEventKeyedOnSomethingOtherThanADocumentStillParses() {
        // operations.period_locked keys on period_code, so a uuid-only key rule would make the one
        // event that is not about a document undeliverable
        listener.onEvent(EventFixtures.periodLocked("2026-08", "ACCOUNTANT"));

        EventEnvelope envelope = captured();
        assertThat(envelope.documentId()).isNull();
        assertThat(envelope.payload()).containsEntry("recipient_role", "ACCOUNTANT");
    }

    private EventEnvelope captured() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(notifications).fanOut(captor.capture());
        return captor.getValue();
    }
}
