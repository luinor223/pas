package com.abclogistics.pas.contract.config;

import com.abclogistics.pas.common.events.ConsumerErrorHandling;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.time.Duration;

/** Consumer failure policy (registry §4): bounded retry, then {@code <topic>.DLT} so the offset advances. */
// unconditional: a @ConditionalOnBean guard vanishes silently, and consumers then fall back to
// Spring's default handler with neither the bounded retry nor the DLT routing §4 requires
@Configuration
public class KafkaConsumerConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafka,
                                          @Value("${contract.kafka.dlt-suffix:.DLT}") String dltSuffix,
                                          @Value("${contract.kafka.retry-attempts:8}") int retries,
                                          @Value("${contract.kafka.retry-backoff:PT2S}") Duration backoff,
                                          @Value("${contract.kafka.max-retry-backoff:PT60S}") Duration maxBackoff) {
        // retries, as in audit and notification: a transient failure is tried retries + 1 times
        return ConsumerErrorHandling.errorHandler(kafka, dltSuffix, retries, backoff, maxBackoff);
    }
}
