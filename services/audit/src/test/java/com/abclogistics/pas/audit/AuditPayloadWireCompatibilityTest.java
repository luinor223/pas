package com.abclogistics.pas.audit;

import com.abclogistics.pas.common.audit.AuditPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payload changed spelling mid-flight. New events are written in the snake_case registry §4
 * documents; the camelCase events already sitting in an outbox row or a retained partition must
 * still parse, or the deploy dead-letters every one of them.
 */
class AuditPayloadWireCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ENTITY_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void newEventsAreWrittenInSnakeCase() {
        String json = MAPPER.writeValueAsString(payload());

        assertThat(json)
                .contains("\"source_service\":\"contract-service\"")
                .contains("\"entity_type\":\"CONTRACT\"")
                .contains("\"before_status\":\"DRAFT\"")
                .contains("\"occurred_at\"");
    }

    @Test
    void noCamelCaseSpellingIsWrittenAnyMore() {
        // one spelling on the wire, or the next reader has to handle both for ever
        String json = MAPPER.writeValueAsString(payload());

        assertThat(json).doesNotContain("sourceService").doesNotContain("entityType")
                .doesNotContain("beforeStatus").doesNotContain("occurredAt");
    }

    @Test
    void aLegacyCamelCaseEventStillParses() {
        // an outbox row written before the fix, published after it
        AuditPayload parsed = MAPPER.readValue(legacyCamelCaseJson(), AuditPayload.class);

        assertThat(parsed.sourceService()).isEqualTo("contract-service");
        assertThat(parsed.entityType()).isEqualTo("CONTRACT");
        assertThat(parsed.entityId()).isEqualTo(ENTITY_ID);
        assertThat(parsed.entityNo()).isEqualTo("HD-2026-0001");
        assertThat(parsed.actorId()).isEqualTo(ACTOR_ID);
        assertThat(parsed.actorName()).isEqualTo("Nguyen Van A");
        assertThat(parsed.actorDepartment()).isEqualTo("SALES");
        assertThat(parsed.beforeStatus()).isEqualTo("DRAFT");
        assertThat(parsed.afterStatus()).isEqualTo("SUBMITTED");
        assertThat(parsed.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(parsed.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void bothSpellingsProduceTheSameRecord() {
        // the required-field check in AuditIngestService reads these five; none may be null
        AuditPayload fromLegacy = MAPPER.readValue(legacyCamelCaseJson(), AuditPayload.class);
        AuditPayload fromCurrent =
                MAPPER.readValue(MAPPER.writeValueAsString(payload()), AuditPayload.class);

        assertThat(fromLegacy).isEqualTo(fromCurrent);
    }

    private static AuditPayload payload() {
        return new AuditPayload("contract-service", "CONTRACT", ENTITY_ID, "HD-2026-0001",
                "UPDATE", ACTOR_ID, "Nguyen Van A", "SALES", "DRAFT", "SUBMITTED",
                Map.of("status", Map.of("before", "DRAFT", "after", "SUBMITTED")), "submitted",
                "127.0.0.1", OCCURRED_AT);
    }

    /** Written by hand, not by the mapper: the old spelling has to outlive the code that wrote it. */
    private static String legacyCamelCaseJson() {
        return """
                {"sourceService":"contract-service","entityType":"CONTRACT","entityId":"%s",
                 "entityNo":"HD-2026-0001","action":"UPDATE","actorId":"%s",
                 "actorName":"Nguyen Van A","actorDepartment":"SALES","beforeStatus":"DRAFT",
                 "afterStatus":"SUBMITTED",
                 "changes":{"status":{"before":"DRAFT","after":"SUBMITTED"}},"note":"submitted",
                 "ipAddress":"127.0.0.1","occurredAt":"2026-09-01T10:00:00Z"}
                """.formatted(ENTITY_ID, ACTOR_ID);
    }
}
