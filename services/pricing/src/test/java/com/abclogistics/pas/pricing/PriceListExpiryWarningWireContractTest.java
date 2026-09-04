package com.abclogistics.pas.pricing;

import com.abclogistics.pas.pricing.dto.ExpiryWarningRow;
import com.abclogistics.pas.pricing.repository.PriceListVersionRepository;
import com.abclogistics.pas.pricing.scheduler.PriceListVersionScheduler;
import com.abclogistics.pas.pricing.service.PriceListVersionService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PriceListExpiryWarningWireContractTest {

    private PriceListVersionRepository versions;
    private PriceListVersionService versionService;
    private KafkaTemplate<String, String> kafka;
    private PriceListVersionScheduler scheduler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        versions = mock(PriceListVersionRepository.class);
        versionService = mock(PriceListVersionService.class);
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);
        scheduler = new PriceListVersionScheduler(
                versionService, versions, provider, objectMapper, 30, true);
    }

    @Test
    void warningCarriesMandatoryHeadersAndBareConsumerPayload() {
        LocalDate today = LocalDate.of(2026, 9, 4);
        UUID versionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ExpiryWarningRow row = new ExpiryWarningRow(
                versionId, 3, today.plusDays(12), ownerId, "PL-2026-0042");
        when(versions.dueForExpiryWarning(today, today.plusDays(30))).thenReturn(List.of(row));

        scheduler.publishExpiryWarnings(today);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();
        var payload = objectMapper.readTree(sent.value());

        assertThat(sent.topic()).isEqualTo("pas.events");
        assertThat(sent.key()).isEqualTo(versionId.toString());
        assertThat(header(sent, "event_type")).isEqualTo("document.expiring");
        assertThat(header(sent, "document_type")).isEqualTo("PRICE_LIST");
        assertThat(UUID.fromString(header(sent, "event_id"))).isNotNull();
        assertThat(payload.get("document_no").asString()).isEqualTo("PL-2026-0042 v3");
        assertThat(payload.get("days_left").asLong()).isEqualTo(12);
        assertThat(payload.get("owner_user_id").asString()).isEqualTo(ownerId.toString());
        assertThat(payload.has("event_id")).isFalse();
        assertThat(payload.has("payload")).isFalse();
        verify(versionService).markExpiryWarned(versionId);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
