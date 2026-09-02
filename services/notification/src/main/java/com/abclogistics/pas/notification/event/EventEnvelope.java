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

    /** The one consumed event that is not about a document: it keys on period_code (registry §4). */
    private static final String PERIOD_LOCKED = "operations.period_locked";

    /** Builds an event from the Kafka key, headers and JSON value. */
    public static EventEnvelope from(ConsumerRecord<String, String> record, ObjectMapper mapper) {
        UUID eventId = EventHeaders.eventId(record);
        String eventType = EventHeaders.required(record, EventHeaders.EVENT_TYPE);
        String documentType = EventHeaders.required(record, EventHeaders.DOCUMENT_TYPE);
        return new EventEnvelope(eventId, eventType, documentType, documentId(record, eventType),
                EventHeaders.payload(record, mapper));
    }

    /** A document event without a usable document key would notify about nothing. */
    private static UUID documentId(ConsumerRecord<String, String> record, String eventType) {
        if (PERIOD_LOCKED.equals(eventType)) {
            return null;
        }
        String key = record.key();
        try {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("blank");
            }
            return UUID.fromString(key);
        } catch (IllegalArgumentException notADocument) {
            throw new MalformedEventException("%s key is not a document id: '%s'"
                    .formatted(eventType, key));
        }
    }
}
