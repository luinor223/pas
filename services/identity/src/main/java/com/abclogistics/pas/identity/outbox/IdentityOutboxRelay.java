package com.abclogistics.pas.identity.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Identity's outbox publisher — currently only {@code audit.recorded} via {@code pas.audit}.
 * Uses the shared M2 claim/publish protocol from {@link OutboxRelay}.
 * Publishing is {@code acks=all} + idempotent per {@code application.yml:26}.
 * Only loaded when relay is enabled to allow tests to disable Kafka.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdentityOutboxRelay extends OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(IdentityOutboxRelay.class);

    private final KafkaTemplate<String, String> kafka;

    public IdentityOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props,
                               KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        super(outbox, props, tx);
        this.kafka = kafka;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        // Block until ack so published_at is stamped only after broker confirms (acks=all)
        kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
        log.debug("Published outbox event {} type={} topic={} key={}", event.getId(),
                event.getEventType(), event.topic(), event.getAggregateId());
    }
}
