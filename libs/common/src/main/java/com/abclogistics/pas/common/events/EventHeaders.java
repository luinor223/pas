package com.abclogistics.pas.common.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The consumer side of registry §4's wire contract, shared because both consumers read it the same
 * way and a second copy is a second chance to drift.
 *
 * <p>The record value is the {@code payload{}} alone; {@code event_id}, {@code event_type} and
 * {@code document_type} travel as headers, set by {@code OutboxRelay#kafkaRecord} for every
 * relayed event and matched by the D9 direct publishers. Reading the type from a header is what
 * lets a consumer skip a record it does not handle without paying to deserialize it.
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

    /**
     * The dedup key. Absent or unparseable is permanent, not transient: without it a redelivery
     * cannot be told from a new event, so no number of retries makes the record safe to process.
     */
    public static UUID eventId(ConsumerRecord<?, ?> record) {
        String value = of(record, EVENT_ID);
        if (value == null) {
            throw new MalformedEventException("missing " + EVENT_ID + " header");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("%s is not a uuid: %s".formatted(EVENT_ID, value));
        }
    }
}
