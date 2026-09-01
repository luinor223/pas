package com.abclogistics.pas.audit.listener;

import com.abclogistics.pas.audit.service.AuditIngestService;
import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The `audit-service` group on `pas.audit` — the topic's only consumer, and the only event on
 * it is `audit.recorded` (registry §4).
 */
@Component
public class AuditEventListener {

    static final String TOPIC = "pas.audit";
    static final String EVENT_TYPE = "audit.recorded";

    private final AuditIngestService ingest;

    public AuditEventListener(AuditIngestService ingest) {
        this.ingest = ingest;
    }

    @KafkaListener(topics = TOPIC, groupId = "audit-service",
            containerFactory = "kafkaListenerContainerFactory",
            autoStartup = "${audit.kafka.listener-enabled:true}")
    public void onAuditRecorded(ConsumerRecord<String, String> record) {
        String eventType = EventHeaders.required(record, EventHeaders.EVENT_TYPE);
        // all three headers are mandatory in §4, so a record missing one is malformed here too
        EventHeaders.required(record, EventHeaders.DOCUMENT_TYPE);
        if (!EVENT_TYPE.equals(eventType)) {
            // not skipped, like the notification listener does for another service's event
            throw new MalformedEventException(
                    "%s carries %s only, got %s".formatted(TOPIC, EVENT_TYPE, eventType));
        }
        ingest.ingest(EventHeaders.eventId(record), record.value());
    }
}
