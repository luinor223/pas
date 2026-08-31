package com.abclogistics.pas.audit.listener;

import com.abclogistics.pas.audit.service.AuditIngestService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The `audit-service` group on `pas.audit` — the topic's only consumer (registry §4). A record that
 * can never be processed goes to `pas.audit.DLT`, because a stuck record blocks its partition.
 */
@Component
public class AuditEventListener {

    private final AuditIngestService ingest;

    public AuditEventListener(AuditIngestService ingest) {
        this.ingest = ingest;
    }

    @KafkaListener(topics = "pas.audit", groupId = "audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onAuditRecorded(ConsumerRecord<String, String> record) {
        throw new UnsupportedOperationException("Phase B: read event_id header, then ingest");
    }
}
