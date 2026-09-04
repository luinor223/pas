package com.abclogistics.pas.common.events;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One wire shape for events published directly instead of through an outbox relay.
 * The value is always the bare event payload; routing and deduplication metadata live in headers.
 */
public final class DirectEventRecord {

    public static final String EVENTS_TOPIC = "pas.events";

    private DirectEventRecord() { }

    public static ProducerRecord<String, String> create(UUID eventId, String eventType,
                                                        String documentType, String key,
                                                        String payloadJson) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(EVENTS_TOPIC, key, payloadJson);
        record.headers().add(header(EventHeaders.EVENT_ID, eventId.toString()));
        record.headers().add(header(EventHeaders.EVENT_TYPE, eventType));
        record.headers().add(header(EventHeaders.DOCUMENT_TYPE, documentType));
        return record;
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
