package com.abclogistics.pas.notification;

import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.notification.config.KafkaConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registry §4 and seq-02(e): "a record that can never succeed goes to {@code pas.events.DLT},
 * because unlike a requeue a stuck record blocks its whole partition." The partition is keyed
 * on the document, so one poison event would stall every document that shares it — which makes
 * the retry/recover split a correctness rule, not tuning.
 */
class DeadLetterRoutingTest {

    private KafkaConsumerConfig config;
    private DefaultErrorHandler handler;
    private KafkaTemplate<String, String> kafka;
    private Consumer<?, ?> consumer;
    private MessageListenerContainer container;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        config = new KafkaConsumerConfig(3, Duration.ofSeconds(2), ".DLT");
        handler = config.errorHandler(kafka);
        consumer = mock(Consumer.class);
        container = mock(MessageListenerContainer.class);
    }

    @Test
    void aMalformedRecordIsRecoveredOnTheFirstAttempt() {
        // no redelivery makes a record without an event_id dedupable
        boolean recovered = handle(new MalformedEventException("missing event_id"), aRecord());

        assertThat(recovered).isTrue();
        verify(kafka).send(any(ProducerRecord.class));
    }

    @Test
    void aTransientFailureIsRetriedRatherThanDeadLettered() {
        // the DB will come back; dead-lettering this one loses a real notification
        boolean recovered = handle(new CannotAcquireLockException("db down"), aRecord());

        assertThat(recovered).isFalse();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aTransientFailureIsGivenUpOnAfterTheConfiguredAttempts() {
        // it cannot retry for ever either — the partition is blocked for the whole of it
        ConsumerRecord<String, String> record = aRecord();
        CannotAcquireLockException stillDown = new CannotAcquireLockException("db down");

        assertThat(handle(stillDown, record)).isFalse();
        assertThat(handle(stillDown, record)).isFalse();
        assertThat(handle(stillDown, record)).isFalse();
        assertThat(handle(stillDown, record)).isTrue();

        verify(kafka).send(any(ProducerRecord.class));
    }

    @Test
    void theConfiguredBackoffIsWhatTheHandlerWaits() {
        // pinned because it is the difference between "briefly delayed" and "partition stalled
        assertThat(config.backOff().getMaxAttempts()).isEqualTo(3);
        assertThat(config.backOff().getInterval()).isEqualTo(Duration.ofSeconds(2).toMillis());
    }

    @Test
    void theDestinationIsTheSourceTopicPlusTheSuffix() {
        ConsumerRecord<String, String> record = aRecord();

        handle(new MalformedEventException("bad"), record);

        assertThat(sentRecord().topic()).isEqualTo("pas.events.DLT");
    }

    @Test
    void theDestinationKeepsTheSourcePartition() {
        // same partition on the DLT as on the source
        ConsumerRecord<String, String> record = aRecord();

        assertThat(config.dlt(record).partition())
                .isEqualTo(record.partition());
    }

    @Test
    void theDeadLetteredRecordKeepsItsKeyAndValue() {
        // a DLT row nobody can replay is a deleted event with extra steps
        ConsumerRecord<String, String> record = aRecord();

        handle(new MalformedEventException("bad"), record);

        assertThat(sentRecord().key()).isEqualTo(record.key());
        assertThat(sentRecord().value()).isEqualTo(record.value());
    }

    @Test
    void theSuffixIsAppliedToWhateverTopicFailed() {
        // each topic gets its own DLT; a shared one would make either service's replay unsafe
        ConsumerRecord<String, String> other = new ConsumerRecord<>("pas.audit", 3, 0L, "k", "{}");

        assertThat(config.dlt(other).topic()).isEqualTo("pas.audit.DLT");
        assertThat(config.dlt(other).partition()).isEqualTo(3);
    }

    /** @return true when the handler recovered the record (sent it onward) instead of retrying */
    private boolean handle(Exception failure, ConsumerRecord<String, String> record) {
        return handler.handleOne(failure, record, consumer, container);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sentRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        return captor.getValue();
    }

    private static ConsumerRecord<String, String> aRecord() {
        return EventFixtures.stepAssigned(UUID.randomUUID(), List.of(UUID.randomUUID()));
    }
}
