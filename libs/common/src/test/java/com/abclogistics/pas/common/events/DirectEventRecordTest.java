package com.abclogistics.pas.common.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DirectEventRecordTest {

    @Test
    void directRecordCanBeReadByTheSharedConsumerContract() {
        UUID eventId = UUID.randomUUID();
        var sent = DirectEventRecord.create(
                eventId, "document.expiring", "PRICE_LIST", "version-42",
                "{\"document_no\":\"PL-2026-0042 v3\",\"days_left\":12}");
        var consumed = new ConsumerRecord<String, String>(
                sent.topic(), 0, 0L, sent.key(), sent.value());
        sent.headers().forEach(consumed.headers()::add);

        assertThat(sent.topic()).isEqualTo("pas.events");
        assertThat(EventHeaders.eventId(consumed)).isEqualTo(eventId);
        assertThat(EventHeaders.required(consumed, EventHeaders.EVENT_TYPE))
                .isEqualTo("document.expiring");
        assertThat(EventHeaders.required(consumed, EventHeaders.DOCUMENT_TYPE))
                .isEqualTo("PRICE_LIST");
        assertThat(EventHeaders.payload(consumed, new ObjectMapper()))
                .containsEntry("document_no", "PL-2026-0042 v3")
                .containsEntry("days_left", 12);
    }
}
