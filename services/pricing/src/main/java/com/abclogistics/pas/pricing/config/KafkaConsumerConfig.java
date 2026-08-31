package com.abclogistics.pas.pricing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Bounded retry for the workflow.completed consumer. A transient failure retries a few times; a
 * persistent one is logged and the offset advances rather than wedging the partition. (A DLQ is
 * future work — session 8.) The processed_event dedup makes the retries safe.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, ex) -> log.error("Giving up on record at {}-{} offset {}: {}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage()),
                new FixedBackOff(1000L, 3));
        return handler;
    }
}
