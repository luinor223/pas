package com.abclogistics.pas.common.correlation;

import com.abclogistics.pas.common.events.EventHeaders;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Consumer side of correlation: lifts {@code X-Correlation-Id} off each record into the MDC before the
 * listener runs, and clears it after. Wired into every listener factory (Boot's auto-configured one picks
 * up this sole {@link RecordInterceptor} bean; services with a hand-built factory set it explicitly).
 */
@Component
public class CorrelationRecordInterceptor implements RecordInterceptor<String, String> {

    @Override
    public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                     Consumer<String, String> consumer) {
        CorrelationSupport.set(CorrelationSupport.orNew(EventHeaders.of(record, CorrelationSupport.KAFKA_HEADER)));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        CorrelationSupport.clear();
    }
}
