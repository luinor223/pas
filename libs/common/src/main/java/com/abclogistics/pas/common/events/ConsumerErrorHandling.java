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

/** Shared retry and dead-letter policy for Kafka consumers. */
public final class ConsumerErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandling.class);

    private ConsumerErrorHandling() { }

    /** Retries transient failures and sends permanent ones directly to the DLT. */
    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafkaTemplate,
                                                   String dltSuffix, int retryAttempts,
                                                   Duration retryBackoff, Duration maxRetryBackoff) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer(kafkaTemplate, dltSuffix, retryAttempts),
                backOff(retryAttempts, retryBackoff, maxRetryBackoff));
        handler.addNotRetryableExceptions(MalformedEventException.class, JacksonException.class);
        return handler;
    }

    public static DeadLetterPublishingRecoverer recoverer(KafkaOperations<?, ?> kafkaTemplate,
                                                          String dltSuffix, int retryAttempts) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate, (record, e) -> {
            TopicPartition target = dlt(record, dltSuffix);
            // Malformed records may use none of the configured retry budget.
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
