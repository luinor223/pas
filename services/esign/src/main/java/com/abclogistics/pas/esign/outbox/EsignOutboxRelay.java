package com.abclogistics.pas.esign.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EsignOutboxRelay extends OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(EsignOutboxRelay.class);

    private final KafkaTemplate<String, String> kafka;

    public EsignOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props,
                             TransactionTemplate tx, KafkaTemplate<String, String> kafka) {
        super(outbox, props, tx);
        this.kafka = kafka;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
        log.debug("Published esign outbox {} type={} topic={}", event.getId(), event.getEventType(), event.topic());
    }
}
