package com.abclogistics.pas.common.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * The consumer side of registry §4's wire contract, shared because both consumers read it the
 * same way and a second copy is a second chance to drift.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "event_id";
    public static final String EVENT_TYPE = "event_type";
    public static final String DOCUMENT_TYPE = "document_type";

    private EventHeaders() { }

    /** @return the header value, or null when the producer did not set it */
    public static String of(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** @throws MalformedEventException when the header is absent or empty */
    public static String required(ConsumerRecord<?, ?> record, String name) {
        String value = of(record, name);
        if (value == null || value.isBlank()) {
            throw new MalformedEventException("missing " + name + " header");
        }
        return value;
    }

    public static UUID eventId(ConsumerRecord<?, ?> record) {
        String value = required(record, EVENT_ID);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("%s is not a uuid: %s".formatted(EVENT_ID, value));
        }
    }

    public static Map<String, Object> payload(ConsumerRecord<?, String> record, ObjectMapper mapper) {
        Object parsed;
        try {
            parsed = mapper.readValue(record.value(), new TypeReference<Object>() { });
        } catch (RuntimeException e) {
            throw new MalformedEventException("value is not JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new MalformedEventException("value is not a JSON object: "
                    + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map;
        return payload;
    }
}
