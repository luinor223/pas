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

    /** Builds an event from the Kafka key, headers and JSON value. */
    public static EventEnvelope from(ConsumerRecord<String, String> record, ObjectMapper mapper) {
        UUID eventId = EventHeaders.eventId(record);
        String eventType = EventHeaders.required(record, EventHeaders.EVENT_TYPE);
        String documentType = EventHeaders.required(record, EventHeaders.DOCUMENT_TYPE);
        return new EventEnvelope(eventId, eventType, documentType, documentId(record),
                EventHeaders.payload(record, mapper));
    }

    /** Non-document events, such as period locks, use a non-UUID key. */
    private static UUID documentId(ConsumerRecord<String, String> record) {
        try {
            return record.key() == null ? null : UUID.fromString(record.key());
        } catch (IllegalArgumentException notADocument) {
            return null;
        }
    }
}
