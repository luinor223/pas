package com.abclogistics.pas.contract.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/**
 * Consumer failure policy (registry §4).
 *
 * <p>A record that can never be processed — a malformed payload, an outcome this service has no
 * status for — must not be retried forever: {@code enable-auto-commit} is false and the container
 * replays the same record, so one poison message blocks its partition and every document behind it
 * stops moving. After a bounded retry it goes to {@code <topic>.DLT} and the offset advances.
 *
 * <p>The retry is what makes the bound safe: a transient failure (the database briefly away) is
 * not a poison record, and dead-lettering one would silently lose a status transition.
 */
@Configuration
@ConditionalOnBean(KafkaTemplate.class)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafka,
                                          @Value("${contract.kafka.dlt-suffix:.DLT}") String dltSuffix,
                                          @Value("${contract.kafka.retry-attempts:3}") int attempts,
                                          @Value("${contract.kafka.retry-backoff:PT2S}") Duration backoff) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> {
                    log.error("Dead-lettering {}-{}@{} after {} attempts: {}", record.topic(),
                            record.partition(), record.offset(), attempts, exception.getMessage());
                    // Same partition on the DLT, so a document's dead records stay in order too.
                    return new TopicPartition(record.topic() + dltSuffix, record.partition());
                });
        // attempts counts the first delivery, so the back-off allows attempts - 1 retries.
        return new DefaultErrorHandler(recoverer,
                new FixedBackOff(backoff.toMillis(), Math.max(0, attempts - 1)));
    }
}
