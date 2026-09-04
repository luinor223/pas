package com.abclogistics.pas.common.correlation;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumer side of correlation: lifts {@code X-Correlation-Id} off each record into the MDC before the
 * listener runs, and clears it after. Boot's auto-configured listener factory applies the sole
 * {@link RecordInterceptor} bean, so every consumer picks this up with no per-service wiring.
 */
@Component
public class CorrelationRecordInterceptor implements RecordInterceptor<String, String> {

    @Override
    public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                     Consumer<String, String> consumer) {
        Header header = record.headers().lastHeader(CorrelationSupport.KAFKA_HEADER);
        String incoming = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        CorrelationSupport.set(CorrelationSupport.orNew(incoming));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        CorrelationSupport.clear();
    }
}
