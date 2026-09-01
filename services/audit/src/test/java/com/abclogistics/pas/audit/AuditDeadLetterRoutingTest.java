package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.config.KafkaConsumerConfig;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * audit-service's own DLT wiring. The policy is shared with notification-service, but the suffix
 * and the properties behind it are this service's, and only a test here would catch them.
 */
class AuditDeadLetterRoutingTest {

    private KafkaConsumerConfig config;
    private DefaultErrorHandler handler;
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        config = new KafkaConsumerConfig(3, Duration.ofSeconds(2), ".DLT");
        handler = config.errorHandler(kafka);
    }

    @Test
    void poisonGoesToTheAuditDlt() {
        handle(new MalformedEventException("no event_id"));

        assertThat(sent().topic()).isEqualTo("pas.audit.DLT");
    }

    @Test
    void aMalformedAuditRecordIsNotRetried() {
        assertThat(handle(new MalformedEventException("wrong event type"))).isTrue();
    }

    @Test
    void aTransientFailureIsRetried() {
        // losing an audit row to the DLT because Postgres blinked would break 4.10's guarantee
        assertThat(handle(new CannotAcquireLockException("db down"))).isFalse();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void theConfiguredPolicyIsThisServicesOwn() {
        assertThat(config.backOff().getMaxAttempts()).isEqualTo(3);
        assertThat(config.backOff().getInterval()).isEqualTo(Duration.ofSeconds(2).toMillis());
    }

    private boolean handle(Exception failure) {
        ConsumerRecord<String, String> record = AuditEventFixtures.recorded(
                AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now()));
        return handler.handleOne(failure, record, mock(org.apache.kafka.clients.consumer.Consumer.class),
                mock(MessageListenerContainer.class));
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sent() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        return captor.getValue();
    }
}
