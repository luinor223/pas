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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The wire contract, which is where this service meets the other seven (registry §4). */
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
        // §4: event_id is in a header precisely so no consumer parses the payload for it
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
        // pas.events carries every event in the system; most are not ours
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
    void aMissingEventTypeHeaderIsMalformedNotSkipped() {
        // the distinction that matters: an event_type that is *present but not ours* is routine
        ConsumerRecord<String, String> headerless = EventRecords.withoutHeader(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                EventHeaders.EVENT_TYPE);

        assertThatThrownBy(() -> listener.onEvent(headerless))
                .isInstanceOf(MalformedEventException.class);
        verify(notifications, never()).fanOut(any());
    }

    @Test
    void aMissingDocumentTypeHeaderIsMalformed() {
        // §4 makes all three headers mandatory; document_type is what the owner-service groups
        ConsumerRecord<String, String> headerless = EventRecords.withoutHeader(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                EventHeaders.DOCUMENT_TYPE);

        assertThatThrownBy(() -> listener.onEvent(headerless))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void anEmptyHeaderCountsAsMissing() {
        // a zero-length header value is a producer bug that would otherwise pass every null check
        ConsumerRecord<String, String> blank = EventRecords.withoutHeader(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                EventHeaders.EVENT_TYPE);
        blank.headers().add(EventHeaders.EVENT_TYPE, "".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> listener.onEvent(blank))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void aValueThatIsJsonButNotAnObjectIsMalformed() {
        // "null", "[]" and "42" all parse; none is a payload
        for (String notAnObject : List.of("null", "[1,2,3]", "42", "\"a string\"")) {
            ConsumerRecord<String, String> record = EventRecords.withValue(
                    EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID())),
                    notAnObject);

            assertThatThrownBy(() -> listener.onEvent(record))
                    .as("value %s", notAnObject)
                    .isInstanceOf(MalformedEventException.class);
        }
    }

    @Test
    void anEventWeConsumeWithNoEventIdIsPermanentlyMalformed() {
        // this is the shape SlaScheduler published before the derived id landed
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
        bad.headers().add(EventHeaders.EVENT_ID, "not-a-uuid".getBytes(StandardCharsets.UTF_8));

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
        // the DB being down must not commit the offset
        org.mockito.Mockito.when(notifications.fanOut(any()))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("db down"));

        assertThatThrownBy(() -> listener.onEvent(
                EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID()))))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
    }

    @Test
    void aRoleAddressedEventKeyedOnSomethingOtherThanADocumentStillParses() {
        // operations.period_locked keys on period_code
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
