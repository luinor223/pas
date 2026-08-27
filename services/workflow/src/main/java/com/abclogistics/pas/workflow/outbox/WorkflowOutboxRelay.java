package com.abclogistics.pas.workflow.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(type = "org.springframework.kafka.core.KafkaTemplate")
public class WorkflowOutboxRelay extends OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOutboxRelay.class);
    private final KafkaTemplate<String, String> kafka;

    public WorkflowOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props, KafkaTemplate<String, String> kafka) {
        super(outbox, props);
        this.kafka = kafka;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        String topic = event.topic();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, event.getAggregateId().toString(), event.getPayload());
        record.headers().add(new RecordHeader("event_type", event.getEventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("document_type", event.getAggregateType().getBytes(StandardCharsets.UTF_8)));
        kafka.send(record).get(5, TimeUnit.SECONDS);
        log.debug("Published workflow outbox {} type={} topic={}", event.getId(), event.getEventType(), topic);
    }
}
