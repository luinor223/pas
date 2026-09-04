package com.abclogistics.pas.audit.config;

import com.abclogistics.pas.common.events.ConsumerErrorHandling;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/** This service's retry/DLT policy, applied by Boot's auto-configured listener factory;
 *  behaviour lives in {@link ConsumerErrorHandling}. */
@Configuration
public class KafkaConsumerConfig {

    private final int retryAttempts;
    private final Duration retryBackoff;
    private final Duration maxRetryBackoff;
    private final String dltSuffix;

    public KafkaConsumerConfig(@Value("${audit.kafka.retry-attempts}") int retryAttempts,
                               @Value("${audit.kafka.retry-backoff}") Duration retryBackoff,
                               @Value("${audit.kafka.max-retry-backoff}") Duration maxRetryBackoff,
                               @Value("${audit.kafka.dlt-suffix}") String dltSuffix) {
        this.retryAttempts = retryAttempts;
        this.retryBackoff = retryBackoff;
        this.maxRetryBackoff = maxRetryBackoff;
        this.dltSuffix = dltSuffix;
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return ConsumerErrorHandling.errorHandler(kafkaTemplate, dltSuffix, retryAttempts, retryBackoff,
                maxRetryBackoff);
    }

    public TopicPartition dlt(ConsumerRecord<?, ?> record) {
        return ConsumerErrorHandling.dlt(record, dltSuffix);
    }

    public ExponentialBackOff backOff() {
        return ConsumerErrorHandling.backOff(retryAttempts, retryBackoff, maxRetryBackoff);
    }
}
