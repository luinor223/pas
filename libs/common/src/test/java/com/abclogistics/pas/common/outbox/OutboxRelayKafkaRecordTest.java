package com.abclogistics.pas.common.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published envelope, read back off a real broker.
 *
 * <p>All three relays now share {@link OutboxRelay#kafkaRecord}, so the shape below is the
 * cross-service contract: consumers key their {@code processed_event} dedup on {@code event_id},
 * discriminate on {@code document_type} before deserializing, and rely on the key being the
 * <em>document</em> id so one document's events stay in one partition (registry §4).
 *
 * <p>Asserted against a broker rather than a mock because a mock can only confirm what this test
 * already believes about the header names — it cannot show that the bytes survive serialization.
 */
@Tag("integration")
@Testcontainers
class OutboxRelayKafkaRecordTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    private static final String TOPIC = "pas.events";

    @AfterAll
    static void stop() {
        kafka.stop();
    }

    @Test
    void thePublishedRecordCarriesTheEnvelopeEveryConsumerReads() throws Exception {
        UUID documentId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.event("workflow.instance_started", "CONTRACT",
                documentId, "{\"instance_id\":\"x\"}");

        ConsumerRecord<String, String> published = roundTrip(event);

        assertThat(published.key()).isEqualTo(documentId.toString());
        assertThat(published.value()).isEqualTo(event.getPayload());
        assertThat(header(published, "event_id")).isEqualTo(event.getId().toString());
        assertThat(header(published, "event_type")).isEqualTo("workflow.instance_started");
        assertThat(header(published, "document_type")).isEqualTo("CONTRACT");
    }

    @Test
    void anAuditRecordGoesToTheAuditTopicWithTheSameEnvelope() {
        OutboxEvent event = OutboxEvent.audit("CUSTOMER", UUID.randomUUID(), "{\"action\":\"CREATE\"}");

        ProducerRecord<String, String> record = new StubRelay().record(event);

        assertThat(record.topic()).isEqualTo("pas.audit");
        assertThat(new String(record.headers().lastHeader("event_id").value(), StandardCharsets.UTF_8))
                .isEqualTo(event.getId().toString());
        assertThat(new String(record.headers().lastHeader("document_type").value(), StandardCharsets.UTF_8))
                .isEqualTo("CUSTOMER");
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Exposes the protected shared builder; the relay's own dependencies are irrelevant here. */
    private static class StubRelay extends OutboxRelay {
        StubRelay() {
            super(null, new OutboxRelayProperties(), null);
        }

        @Override
        protected void dispatch(OutboxEvent event) { }

        ProducerRecord<String, String> record(OutboxEvent event) {
            return kafkaRecord(event);
        }
    }

    private ConsumerRecord<String, String> roundTrip(OutboxEvent event) throws Exception {
        Map<String, Object> producerProps = new HashMap<>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "acks", "all"));
        KafkaTemplate<String, String> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
        try {
            template.send(new StubRelay().record(event)).get(10, TimeUnit.SECONDS);
        } finally {
            template.destroy();
        }

        Map<String, Object> consumerProps = new HashMap<>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", "envelope-test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest"));
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(20));
            assertThat(records.count()).as("one record on %s", TOPIC).isEqualTo(1);
            return records.iterator().next();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
