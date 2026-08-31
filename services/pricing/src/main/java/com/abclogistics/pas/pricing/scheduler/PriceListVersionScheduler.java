package com.abclogistics.pas.pricing.scheduler;

import com.abclogistics.pas.pricing.dto.ExpiryWarningRow;
import com.abclogistics.pas.pricing.repository.PriceListVersionRepository;
import com.abclogistics.pas.pricing.service.PriceListVersionService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Date-driven version flips (§9) and the expiry warning (D9). Every sweep is self-healing: each
 * version gets its own transaction and its own try, so one that cannot move does not stop the run.
 */
@Component
public class PriceListVersionScheduler {

    static final String EVENTS_TOPIC = "pas.events";
    static final String DOCUMENT_EXPIRING = "document.expiring";
    private static final String DOCUMENT_TYPE = "PRICE_LIST";

    private static final Logger log = LoggerFactory.getLogger(PriceListVersionScheduler.class);

    private final PriceListVersionService versionService;
    private final PriceListVersionRepository versions;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaProvider;
    private final ObjectMapper objectMapper;
    private final int warningDays;
    private final boolean enabled;

    public PriceListVersionScheduler(PriceListVersionService versionService, PriceListVersionRepository versions,
                                     ObjectProvider<KafkaTemplate<String, String>> kafkaProvider,
                                     ObjectMapper objectMapper,
                                     @Value("${pricing.expiry-warning-days:30}") int warningDays,
                                     @Value("${pricing.status-sweep-enabled:true}") boolean enabled) {
        this.versionService = versionService;
        this.versions = versions;
        this.kafkaProvider = kafkaProvider;
        this.objectMapper = objectMapper;
        this.warningDays = warningDays;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${pricing.status-sweep-interval:PT60S}")
    public void runSweep() {
        if (enabled) {
            sweep();
        }
    }

    /** The sweep, callable without the schedule. today is read once and threaded through. */
    public void sweep() {
        LocalDate today = LocalDate.now();
        activateDue(today);
        expireDue(today);
        publishExpiryWarnings(today);
    }

    public void activateDue(LocalDate today) {
        for (UUID id : versions.dueForActivation(today)) {
            try {
                versionService.activate(id);
            } catch (Exception e) {
                log.warn("Could not activate price list version {}: {}", id, e.getMessage());
            }
        }
    }

    public void expireDue(LocalDate today) {
        for (UUID id : versions.dueForExpiry(today)) {
            try {
                versionService.expire(id);
            } catch (Exception e) {
                log.warn("Could not expire price list version {}: {}", id, e.getMessage());
            }
        }
    }

    /** D9: published directly, no outbox row — the stamp is written only after the ack, so a lost
     *  warning re-fires next sweep. */
    public void publishExpiryWarnings(LocalDate today) {
        LocalDate horizon = today.plusDays(warningDays);
        KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
        if (kafka == null) {
            log.debug("Kafka unavailable; expiry warnings re-fire next run");
            return;
        }
        for (ExpiryWarningRow row : versions.dueForExpiryWarning(today, horizon)) {
            try {
                kafka.send(record(row, today)).get(5, TimeUnit.SECONDS);
                versionService.markExpiryWarned(row.versionId());   // only after the ack
                log.info("Published {} for {} v{}", DOCUMENT_EXPIRING, row.priceListNo(), row.versionNo());
            } catch (Exception e) {
                log.warn("Could not publish {} for version {}: {}", DOCUMENT_EXPIRING, row.versionId(), e.getMessage());
            }
        }
    }

    private ProducerRecord<String, String> record(ExpiryWarningRow row, LocalDate today) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_no", row.priceListNo() + " v" + row.versionNo());
        payload.put("expires_on", row.validTo().toString());
        payload.put("days_left", ChronoUnit.DAYS.between(today, row.validTo()));
        payload.put("owner_user_id", row.ownerId() == null ? null : row.ownerId().toString());

        UUID eventId = eventId(row);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId.toString());
        envelope.put("event_type", DOCUMENT_EXPIRING);
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("actor_id", null);
        envelope.put("actor_name", "system");
        envelope.put("document_type", DOCUMENT_TYPE);
        envelope.put("document_id", row.versionId().toString());
        envelope.put("payload", payload);

        ProducerRecord<String, String> record = new ProducerRecord<>(EVENTS_TOPIC,
                row.versionId().toString(), objectMapper.writeValueAsString(envelope));
        record.headers().add(header("event_type", DOCUMENT_EXPIRING));
        record.headers().add(header("document_type", DOCUMENT_TYPE));
        record.headers().add(header("event_id", eventId.toString()));
        return record;
    }

    /** Derived id so a crash between publish and stamp re-warns without a new event; valid_to is in
     *  the key so a truncated version earns a fresh id. */
    static UUID eventId(ExpiryWarningRow row) {
        String name = "%s:%s:%s".formatted(DOCUMENT_EXPIRING, row.versionId(), row.validTo());
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
