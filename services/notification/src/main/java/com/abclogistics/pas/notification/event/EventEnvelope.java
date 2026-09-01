package com.abclogistics.pas.notification.event;

import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * One consumed event, assembled from the wire rather than parsed out of the value.
 *
 * <p>Registry §4: the record value is the {@code payload{}} alone and the fields a consumer needs
 * travel as headers — {@code event_id} (the dedup key), {@code event_type}, {@code document_type}
 * — with the partition key carrying the document id. That is what {@code OutboxRelay#kafkaRecord}
 * publishes for all seven producers and what the D9 direct publishers match, so there is one parse
 * path and no event is special-cased.
 *
 * <p>{@code payload} stays a raw map: notification reads a handful of recipient keys out of it and
 * interprets nothing else.
 */
public record EventEnvelope(UUID eventId, String eventType,
                            String documentType, UUID documentId,
                            Map<String, Object> payload) {

    /**
     * @throws MalformedEventException when a required header is missing or unparseable, or the
     *         value is not a JSON object — all permanent, so the listener lets them reach the DLT
     *         rather than retrying a record no redelivery can fix.
     */
    public static EventEnvelope from(ConsumerRecord<String, String> record, ObjectMapper mapper) {
        String eventType = EventHeaders.of(record, EventHeaders.EVENT_TYPE);
        if (eventType == null) {
            throw new MalformedEventException("missing " + EventHeaders.EVENT_TYPE + " header");
        }
        UUID eventId = EventHeaders.eventId(record);
        String documentType = EventHeaders.of(record, EventHeaders.DOCUMENT_TYPE);

        Map<String, Object> payload;
        try {
            payload = mapper.readValue(record.value(), new TypeReference<>() { });
        } catch (RuntimeException e) {
            throw new MalformedEventException("value is not a JSON object: " + e.getMessage());
        }
        return new EventEnvelope(eventId, eventType, documentType, documentId(record), payload);
    }

    /**
     * The partition key is the aggregate id, which for every <em>document</em> event in §4 is the
     * document. {@code operations.period_locked} is the exception — it is not about a document and
     * keys on {@code period_code} — so a non-uuid key means "no document", not a bad record.
     */
    private static UUID documentId(ConsumerRecord<String, String> record) {
        try {
            return record.key() == null ? null : UUID.fromString(record.key());
        } catch (IllegalArgumentException notADocument) {
            return null;
        }
    }
}
