package com.abclogistics.pas.audit.listener;

import com.abclogistics.pas.audit.service.AuditIngestService;
import com.abclogistics.pas.common.events.EventHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The `audit-service` group on `pas.audit` — the topic's only consumer, and the only event on it
 * is `audit.recorded` (registry §4).
 *
 * <p>Two differences from the notification listener, both deliberate. There is no event-type filter
 * worth applying: everything on this topic is ours, so a record with an unexpected type is a
 * producer bug rather than routine traffic and must not be silently dropped. And there is no
 * `processed_event` lookup: the row's primary key *is* the `event_id`, so the insert dedups itself
 * (db-audit.md) and a redelivery costs one conflicting insert rather than a read plus a write.
 */
@Component
public class AuditEventListener {

    private final AuditIngestService ingest;

    public AuditEventListener(AuditIngestService ingest) {
        this.ingest = ingest;
    }

    @KafkaListener(topics = "pas.audit", groupId = "audit-service",
            containerFactory = "kafkaListenerContainerFactory",
            autoStartup = "${audit.kafka.listener-enabled:true}")
    public void onAuditRecorded(ConsumerRecord<String, String> record) {
        ingest.ingest(EventHeaders.eventId(record), record.value());
    }
}
