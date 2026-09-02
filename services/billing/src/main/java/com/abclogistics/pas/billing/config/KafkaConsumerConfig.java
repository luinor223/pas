package com.abclogistics.pas.billing.config;

import com.abclogistics.pas.common.events.ConsumerErrorHandling;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.time.Duration;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler billingErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return ConsumerErrorHandling.errorHandler(kafkaTemplate, ".DLT", 3, Duration.ofSeconds(1));
    }
}
