package com.abclogistics.pas.contract.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** Consumer failure policy (registry §4): bounded retry, then {@code <topic>.DLT} so the offset advances. */
// unconditional: a @ConditionalOnBean guard vanishes silently, and consumers then fall back to
// Spring's default handler with neither the bounded retry nor the DLT routing §4 requires
@Configuration
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
