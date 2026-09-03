package com.abclogistics.pas.common.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The wire shape, built by the producer rather than restated by the consumer. */
public final class EventRecords {

    /** {@link OutboxRelay} exists to be subclassed per service; this one only shapes records. */
    private static final OutboxRelay SHAPER = new OutboxRelay(null, null, null) {
        @Override
        protected void dispatch(OutboxEvent event) {
            throw new UnsupportedOperationException("fixture: shapes records, never dispatches");
        }
    };

    private EventRecords() { }

    /** A relayed event, shaped by the relay itself. */
    public static ProducerRecord<String, String> outboxed(String eventType, String documentType,
                                                          UUID documentId, String payloadJson) {
        return SHAPER.kafkaRecord(OutboxEvent.event(eventType, documentType, documentId, payloadJson));
    }

    /** An {@code audit.recorded} event, which the relay routes to {@code pas.audit}. */
    public static ProducerRecord<String, String> auditRecorded(String documentType, UUID documentId,
                                                               String payloadJson) {
        return SHAPER.kafkaRecord(OutboxEvent.audit(documentType, documentId, payloadJson));
    }

    /**
     * A D9 direct publish — no outbox row, so the caller supplies the derived {@code event_id}
     * that makes the event dedupable across re-sends.
     */
    public static ProducerRecord<String, String> directPublish(UUID eventId, String eventType,
                                                               String documentType, UUID documentId,
                                                               String payloadJson) {
        return directPublish(eventId, eventType, documentType, documentId.toString(), payloadJson);
    }

    /**
     * The same, for the one direct-publish event that is not about a document: {@code
     * operations.period_locked} keys on {@code period_code} (registry §4).
     */
    public static ProducerRecord<String, String> directPublish(UUID eventId, String eventType,
                                                               String documentType, String key,
                                                               String payloadJson) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>("pas.events", key, payloadJson);
        record.headers().add(header("event_id", eventId.toString()));
        record.headers().add(header("event_type", eventType));
        record.headers().add(header("document_type", documentType));
        return record;
    }

    /** What the listener is actually handed, from what the producer actually sends. */
    public static ConsumerRecord<String, String> consumed(ProducerRecord<String, String> sent) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                sent.topic(), 0, 0L, sent.key(), sent.value());
        for (Header h : sent.headers()) {
            record.headers().add(h);
        }
        return record;
    }

    /** A record with one header removed or corrupted — the malformed-input half of a listener spec. */
    public static ConsumerRecord<String, String> withoutHeader(ConsumerRecord<String, String> record,
                                                              String name) {
        ConsumerRecord<String, String> stripped = new ConsumerRecord<>(
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
        for (Header h : record.headers()) {
            if (!h.key().equals(name)) {
                stripped.headers().add(h);
            }
        }
        return stripped;
    }

    /** A record re-keyed, for the half of the key contract a producer can get wrong. */
    public static ConsumerRecord<String, String> withKey(ConsumerRecord<String, String> record,
                                                         String key) {
        ConsumerRecord<String, String> rekeyed = new ConsumerRecord<>(
                record.topic(), record.partition(), record.offset(), key, record.value());
        for (Header h : record.headers()) {
            rekeyed.headers().add(h);
        }
        return rekeyed;
    }

    public static ConsumerRecord<String, String> withValue(ConsumerRecord<String, String> record,
                                                           String value) {
        ConsumerRecord<String, String> replaced = new ConsumerRecord<>(
                record.topic(), record.partition(), record.offset(), record.key(), value);
        for (Header h : record.headers()) {
            replaced.headers().add(h);
        }
        return replaced;
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
