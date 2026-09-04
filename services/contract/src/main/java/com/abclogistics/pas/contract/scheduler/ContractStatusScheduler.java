package com.abclogistics.pas.contract.scheduler;

import com.abclogistics.pas.common.events.DirectEventRecord;
import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.ContractService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Date-driven transitions (D14d) and the expiry warning (D9). Every sweep is self-healing. */
@Component
public class ContractStatusScheduler {

    static final String EVENTS_TOPIC = "pas.events";
    static final String DOCUMENT_EXPIRING = "document.expiring";
    private static final String DOCUMENT_TYPE = "CONTRACT";

    private static final Logger log = LoggerFactory.getLogger(ContractStatusScheduler.class);

    private final ContractService contracts;
    private final AddendumService addenda;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaProvider;
    private final ObjectMapper objectMapper;
    private final int warningDays;
    private final boolean enabled;

    public ContractStatusScheduler(ContractService contracts, AddendumService addenda,
                                   ObjectProvider<KafkaTemplate<String, String>> kafkaProvider,
                                   ObjectMapper objectMapper,
                                   @Value("${contract.expiry-warning-days}") int warningDays,
                                   @Value("${contract.status-sweep-enabled:true}") boolean enabled) {
        this.contracts = contracts;
        this.addenda = addenda;
        this.kafkaProvider = kafkaProvider;
        this.objectMapper = objectMapper;
        this.warningDays = warningDays;
        this.enabled = enabled;
    }

    /**
     * One scheduled entry point, because the four sweeps are ordered, not independent. An
     * addendum extending a contract past today has to activate BEFORE the expiry sweep looks at
     * the parent; scheduling the four separately would let the expiry run first on some runs,
     * expire the contract, and then fail the extension for ever (its parent is no longer
     * amendable).
     */
    @Scheduled(fixedDelayString = "${contract.status-sweep-interval}")
    public void runSweep() {
        if (enabled) {
            sweep();
        }
    }

    /**
     * The ordered sweep, callable without the schedule. Every document gets its own transaction
     * and its own try: one that cannot move must not end the run. {@code today} is read once
     * and threaded through, or a sweep starting at 23:59:59 judges its four passes against two
     * dates.
     */
    public void sweep() {
        LocalDate today = LocalDate.now();
        activateDueAddenda(today);     // renewals land on the parent first
        activateDueContracts(today);
        expireEndedContracts(today);   // ... so this sees the extended valid_to
        publishExpiryWarnings(today);
    }

    /** APPROVED -> ACTIVE at valid_from (CTR-05). */
    public void activateDueContracts(LocalDate today) {
        for (UUID id : contracts.dueForActivation(today)) {
            try {
                contracts.activate(id);
            } catch (Exception e) {
                log.warn("Could not activate contract {}: {}", id, e.getMessage());
            }
        }
    }

    /** ACTIVE -> EXPIRED after valid_to. */
    public void expireEndedContracts(LocalDate today) {
        for (UUID id : contracts.dueForExpiry(today)) {
            try {
                contracts.expire(id, today);
            } catch (Exception e) {
                log.warn("Could not expire contract {}: {}", id, e.getMessage());
            }
        }
    }

    /** APPROVED -> ACTIVE at effective_from, applying the addendum's effects to its parent (§9²). */
    public void activateDueAddenda(LocalDate today) {
        for (Addendum addendum : addenda.dueForActivation(today)) {
            try {
                addenda.activate(addendum.getId());
            } catch (Exception e) {
                // e.g. a parent cancelled mid-approval: refused, and refused again next run
                log.warn("Could not activate addendum {}: {}", addendum.getId(), e.getMessage());
            }
        }
    }

    /**
     * D9: published directly, no outbox row — the stamp is written only after the ack, so a
     * lost warning re-fires next sweep and an outbox row would buy nothing.
     */
    public void publishExpiryWarnings(LocalDate today) {
        LocalDate horizon = today.plusDays(warningDays);
        KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
        if (kafka == null) {
            // nothing is stamped, so nothing is lost
            log.debug("Kafka unavailable; expiry warnings re-fire next run");
            return;
        }
        for (ContractService.ExpiryWarning warning : contracts.dueForExpiryWarning(today, horizon)) {
            try {
                kafka.send(record(warning)).get(5, TimeUnit.SECONDS);
                // only now: an unstamped contract is simply a candidate again next run
                contracts.markExpiryWarned(warning.contractId(), warning.expiresOn());
                log.info("Published {} for {} ({} days left)", DOCUMENT_EXPIRING,
                        warning.documentNo(), warning.daysLeft());
            } catch (Exception e) {
                log.warn("Could not publish {} for {}: {}", DOCUMENT_EXPIRING,
                        warning.documentNo(), e.getMessage());
            }
        }
    }

    private ProducerRecord<String, String> record(ContractService.ExpiryWarning warning) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_no", warning.documentNo());
        payload.put("expires_on", warning.expiresOn().toString());
        payload.put("days_left", warning.daysLeft());
        payload.put("owner_user_id", warning.ownerUserId());

        UUID eventId = eventId(warning);

        // value is the bare payload, exactly as OutboxRelay#kafkaRecord publishes every outboxe
        return DirectEventRecord.create(eventId, DOCUMENT_EXPIRING, DOCUMENT_TYPE,
                warning.contractId().toString(), objectMapper.writeValueAsString(payload));
    }

    /**
     * Derived, not random — what makes this event retryable without an outbox row. The ack and
     * the stamp cannot be atomic, so a crash between them re-warns and two replicas can both
     * send; a fresh uuid would make each of those a new event the consumer cannot dedupe.
     * valid_to is in the key so an extension earns a new id (registry §4 change log).
     */
    static UUID eventId(ContractService.ExpiryWarning warning) {
        String name = "%s:%s:%s".formatted(
                DOCUMENT_EXPIRING, warning.contractId(), warning.expiresOn());
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

}
