package com.abclogistics.pas.notification.config;

import com.abclogistics.pas.common.events.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

import java.time.Duration;

/**
 * The consumer half of registry §4's "a record that can never be processed goes to
 * {@code <topic>.DLT} so the offset can advance".
 *
 * <p>Unlike a requeue, a stuck record blocks its whole partition — and the partition is keyed on
 * the document, so one poison event about one contract would stall every event about every
 * document that hashes with it. Hence the split the tests pin: a **transient** failure (Postgres
 * down, identity unreachable) is retried with backoff, because it will succeed later; a
 * **permanent** one (no {@code event_id} header, a value that is not JSON) goes to the DLT on the
 * first attempt, because no number of retries makes it parseable and each one costs the partition.
 */
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
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DefaultErrorHandler handler =
                new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate, this::dlt), backOff());
        handler.addNotRetryableExceptions(MalformedEventException.class, JacksonException.class);
        return handler;
    }

    /**
     * Where a record goes when it cannot be recovered: {@code <topic>.DLT}, on the <b>same
     * partition</b> it failed on, so one document's failures stay together and stay replayable in
     * the order they happened. Each topic gets its own DLT — mixing {@code pas.events} and
     * {@code pas.audit} poison would make either one's replay unsafe.
     */
    public TopicPartition dlt(ConsumerRecord<?, ?> record, Exception exception) {
        return new TopicPartition(record.topic() + dltSuffix, record.partition());
    }

    /**
     * {@code retryAttempts} counts <em>retries</em>, so a transient failure is attempted
     * {@code retryAttempts + 1} times before recovery. These are correctness-adjacent, not tuning:
     * the whole partition waits out every backoff.
     */
    public FixedBackOff backOff() {
        return new FixedBackOff(retryBackoff.toMillis(), retryAttempts);
    }
}
