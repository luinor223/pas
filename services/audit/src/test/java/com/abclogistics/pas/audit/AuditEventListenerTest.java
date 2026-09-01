package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.listener.AuditEventListener;
import com.abclogistics.pas.audit.service.AuditIngestService;
import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.common.outbox.EventRecords;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wire contract on {@code pas.audit}. The same gap the notification listener had: nothing
 * checked what arrives, so nothing would have caught the value being a payload rather than an
 * envelope.
 */
class AuditEventListenerTest {

    private AuditIngestService ingest;
    private AuditEventListener listener;

    @BeforeEach
    void setUp() {
        ingest = mock(AuditIngestService.class);
        listener = new AuditEventListener(ingest);
    }

    @Test
    void theDedupKeyIsTheEventIdHeader() {
        // this id becomes the row's primary key — the reason this service needs no processed_event
        ConsumerRecord<String, String> record = AuditEventFixtures.recorded(
                AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now()));

        listener.onAuditRecorded(record);

        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(ingest).ingest(id.capture(), anyString());
        assertThat(id.getValue())
                .isEqualTo(UUID.fromString(EventHeaders.of(record, EventHeaders.EVENT_ID)));
    }

    @Test
    void theValueHandedOnIsThePayloadNotAnEnvelope() {
        ConsumerRecord<String, String> record = AuditEventFixtures.recorded(
                AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now()));

        listener.onAuditRecorded(record);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(ingest).ingest(any(), value.capture());
        // snake_case, the names registry §4 lists and every other event's payload uses
        assertThat(value.getValue())
                .contains("\"source_service\":\"contract-service\"")
                .doesNotContain("\"sourceService\"")
                .doesNotContain("\"payload\"")
                .doesNotContain("\"event_id\"");
    }

    @Test
    void aRecordWithNoEventIdIsPermanentlyMalformed() {
        // there is no second key to fall back on: without the header the row has no primary key
        ConsumerRecord<String, String> noId = EventRecords.withoutHeader(
                AuditEventFixtures.recorded(
                        AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now())),
                EventHeaders.EVENT_ID);

        assertThatThrownBy(() -> listener.onAuditRecorded(noId))
                .isInstanceOf(MalformedEventException.class);
        verify(ingest, never()).ingest(any(), anyString());
    }

    @Test
    void anEventTypeOtherThanAuditRecordedNeverReachesIngest() {
        // pas.audit carries one event type and this is its only consumer (registry §4)
        ConsumerRecord<String, String> odd = EventRecords.consumed(EventRecords.outboxed(
                "something.unexpected", "CONTRACT", UUID.randomUUID(), "{}"));

        assertThatThrownBy(() -> listener.onAuditRecorded(odd))
                .isInstanceOf(MalformedEventException.class);
        verify(ingest, never()).ingest(any(), anyString());
    }

    @Test
    void aMissingEventTypeHeaderIsMalformed() {
        ConsumerRecord<String, String> headerless = EventRecords.withoutHeader(
                AuditEventFixtures.recorded(
                        AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now())),
                EventHeaders.EVENT_TYPE);

        assertThatThrownBy(() -> listener.onAuditRecorded(headerless))
                .isInstanceOf(MalformedEventException.class);
        verify(ingest, never()).ingest(any(), anyString());
    }

    @Test
    void aMissingDocumentTypeHeaderIsMalformedToo() {
        // §4 makes all three mandatory; validating two of them is not the contract
        ConsumerRecord<String, String> headerless = EventRecords.withoutHeader(
                AuditEventFixtures.recorded(
                        AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now())),
                EventHeaders.DOCUMENT_TYPE);

        assertThatThrownBy(() -> listener.onAuditRecorded(headerless))
                .isInstanceOf(MalformedEventException.class);
        verify(ingest, never()).ingest(any(), anyString());
    }

    @Test
    void aGenuineAuditRecordedEventIsIngested() {
        listener.onAuditRecorded(AuditEventFixtures.recorded(
                AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now())));

        verify(ingest).ingest(any(), anyString());
    }

    @Test
    void aTransientIngestFailureIsRethrown() {
        // swallowing commits the offset and loses an audit row permanently — 4.10 is unconditional
        when(ingest.ingest(any(), anyString()))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("db down"));

        assertThatThrownBy(() -> listener.onAuditRecorded(AuditEventFixtures.recorded(
                AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now()))))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
    }
}
