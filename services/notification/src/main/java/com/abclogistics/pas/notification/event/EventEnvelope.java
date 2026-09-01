package com.abclogistics.pas.notification.event;

import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/** One consumed event, assembled from the wire rather than parsed out of the value. */
public record EventEnvelope(UUID eventId, String eventType,
                            String documentType, UUID documentId,
                            Map<String, Object> payload) {

    /**
     * @throws MalformedEventException when a required header is missing or unparseable, or the
     * value is not a JSON object — all permanent, so the listener lets them reach the DLT
     * rather than retrying a record no redelivery can fix.
     */
    public static EventEnvelope from(ConsumerRecord<String, String> record, ObjectMapper mapper) {
        UUID eventId = EventHeaders.eventId(record);
        String eventType = EventHeaders.required(record, EventHeaders.EVENT_TYPE);
        String documentType = EventHeaders.required(record, EventHeaders.DOCUMENT_TYPE);
        return new EventEnvelope(eventId, eventType, documentType, documentId(record),
                EventHeaders.payload(record, mapper));
    }

    /**
     * The partition key is the aggregate id, which for every <em>document</em> event in §4 is
     * the document. {@code operations.period_locked} is the exception — it is not about a
     * document and keys on {@code period_code} — so a non-uuid key means "no document", not a
     * bad record.
     */
    private static UUID documentId(ConsumerRecord<String, String> record) {
        try {
            return record.key() == null ? null : UUID.fromString(record.key());
        } catch (IllegalArgumentException notADocument) {
            return null;
        }
    }
}
