package com.abclogistics.pas.common.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

import java.time.Duration;

/**
 * Registry §4's "a record that can never succeed goes to {@code <topic>.DLT}", built once for both
 * consumers so a fix to one is a fix to both.
 */
public final class ConsumerErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandling.class);

    private ConsumerErrorHandling() { }

    /**
     * Transient failures retry with exponential backoff; permanent ones go to the DLT on the first
     * attempt. The partition is keyed on the document, so every wasted attempt stalls other
     * documents too — but the budget still has to outlast a database or identity restart, or a
     * restart turns into a hole in the audit trail.
     *
     * @param retryAttempts number of <em>retries</em>, so a transient failure is tried n + 1 times
     */
    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafkaTemplate,
                                                   String dltSuffix, int retryAttempts,
                                                   Duration retryBackoff, Duration maxRetryBackoff) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer(kafkaTemplate, dltSuffix, retryAttempts),
                backOff(retryAttempts, retryBackoff, maxRetryBackoff));
        handler.addNotRetryableExceptions(MalformedEventException.class, JacksonException.class);
        return handler;
    }

    /** Dead-lettering is logged at ERROR: nothing else tells an operator the record needs replaying. */
    public static DeadLetterPublishingRecoverer recoverer(KafkaOperations<?, ?> kafkaTemplate,
                                                          String dltSuffix, int retryAttempts) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate, (record, e) -> {
            TopicPartition target = dlt(record, dltSuffix);
            // the budget, not the attempts spent: a malformed record is recovered without any retry
            log.error("Dead-lettering {}-{}@{} to {} (retry budget {}); replay it once the cause is "
                    + "fixed", record.topic(), record.partition(), record.offset(), target.topic(),
                    retryAttempts, e);
            return target;
        });
    }

    /** {@code <topic><suffix>}, same partition — one document's failures stay replayable in order. */
    public static TopicPartition dlt(ConsumerRecord<?, ?> record, String dltSuffix) {
        return new TopicPartition(record.topic() + dltSuffix, record.partition());
    }

    /** Doubling from {@code retryBackoff}, capped at {@code maxRetryBackoff}. */
    public static ExponentialBackOff backOff(int retryAttempts, Duration retryBackoff,
                                             Duration maxRetryBackoff) {
        ExponentialBackOff backOff = new ExponentialBackOff(retryBackoff.toMillis(), 2.0);
        backOff.setMaxInterval(maxRetryBackoff.toMillis());
        backOff.setMaxAttempts(retryAttempts);
        return backOff;
    }
}
