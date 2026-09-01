package com.abclogistics.pas.common.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

import java.time.Duration;

/**
 * Registry §4's "a record that can never succeed goes to {@code <topic>.DLT}", built once for both
 * consumers so a fix to one is a fix to both.
 */
public final class ConsumerErrorHandling {

    private ConsumerErrorHandling() { }

    /**
     * Transient failures retry with backoff; permanent ones go to the DLT on the first attempt.
     * The partition is keyed on the document, so every wasted attempt stalls other documents too.
     *
     * @param retryAttempts number of <em>retries</em>, so a transient failure is tried n + 1 times
     */
    public static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                   String dltSuffix, int retryAttempts,
                                                   Duration retryBackoff) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate, (record, e) -> dlt(record, dltSuffix)),
                backOff(retryAttempts, retryBackoff));
        handler.addNotRetryableExceptions(MalformedEventException.class, JacksonException.class);
        return handler;
    }

    /** {@code <topic><suffix>}, same partition — one document's failures stay replayable in order. */
    public static TopicPartition dlt(ConsumerRecord<?, ?> record, String dltSuffix) {
        return new TopicPartition(record.topic() + dltSuffix, record.partition());
    }

    public static FixedBackOff backOff(int retryAttempts, Duration retryBackoff) {
        return new FixedBackOff(retryBackoff.toMillis(), retryAttempts);
    }
}
