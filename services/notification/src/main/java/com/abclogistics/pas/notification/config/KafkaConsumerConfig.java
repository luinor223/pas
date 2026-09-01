package com.abclogistics.pas.notification.config;

import com.abclogistics.pas.common.events.ConsumerErrorHandling;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** This service's retry/DLT policy; the behaviour lives in {@link ConsumerErrorHandling}. */
@Configuration
public class KafkaConsumerConfig {

    private final int retryAttempts;
    private final Duration retryBackoff;
    private final String dltSuffix;

    public KafkaConsumerConfig(@Value("${notification.kafka.retry-attempts}") int retryAttempts,
                               @Value("${notification.kafka.retry-backoff}") Duration retryBackoff,
                               @Value("${notification.kafka.dlt-suffix}") String dltSuffix) {
        this.retryAttempts = retryAttempts;
        this.retryBackoff = retryBackoff;
        this.dltSuffix = dltSuffix;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return ConsumerErrorHandling.errorHandler(kafkaTemplate, dltSuffix, retryAttempts, retryBackoff);
    }

    public TopicPartition dlt(ConsumerRecord<?, ?> record) {
        return ConsumerErrorHandling.dlt(record, dltSuffix);
    }

    public FixedBackOff backOff() {
        return ConsumerErrorHandling.backOff(retryAttempts, retryBackoff);
    }
}
