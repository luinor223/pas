package com.abclogistics.pas.audit.listener;

import com.abclogistics.pas.audit.service.AuditIngestService;
import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes {@code audit.recorded} from {@code pas.audit}. */
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
        // Registry §4 requires all three headers.
        EventHeaders.required(record, EventHeaders.DOCUMENT_TYPE);
        if (!EVENT_TYPE.equals(eventType)) {
            throw new MalformedEventException(
                    "%s carries %s only, got %s".formatted(TOPIC, EVENT_TYPE, eventType));
        }
        ingest.ingest(EventHeaders.eventId(record), record.value());
    }
}
