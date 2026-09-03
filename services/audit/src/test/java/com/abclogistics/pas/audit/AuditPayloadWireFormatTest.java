package com.abclogistics.pas.audit;

import com.abclogistics.pas.common.audit.AuditPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One spelling on the wire, the one registry §4 documents. No camelCase compatibility: nothing
 * is in flight to be compatible with — the stack is wiped with {@code make down-v} between runs.
 */
class AuditPayloadWireFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theEventIsWrittenInSnakeCase() {
        String json = MAPPER.writeValueAsString(payload());

        assertThat(json)
                .contains("\"source_service\":\"contract-service\"")
                .contains("\"entity_type\":\"CONTRACT\"")
                .contains("\"entity_no\":\"HD-2026-0001\"")
                .contains("\"before_status\":\"DRAFT\"")
                .contains("\"after_status\":\"SUBMITTED\"")
                .contains("\"occurred_at\"");
    }

    @Test
    void noCamelCaseSpellingSurvives() {
        // the defect this pins: a Java record serializes to camelCase unless told otherwise
        String json = MAPPER.writeValueAsString(payload());

        assertThat(json).doesNotContain("sourceService").doesNotContain("entityType")
                .doesNotContain("beforeStatus").doesNotContain("occurredAt")
                .doesNotContain("actorDepartment").doesNotContain("ipAddress");
    }

    @Test
    void whatIsWrittenIsWhatIsRead() {
        AuditPayload original = payload();

        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(original), AuditPayload.class))
                .isEqualTo(original);
    }

    private static AuditPayload payload() {
        return new AuditPayload("contract-service", "CONTRACT", UUID.randomUUID(), "HD-2026-0001",
                "UPDATE", UUID.randomUUID(), "Nguyen Van A", "SALES", "DRAFT", "SUBMITTED",
                Map.of("status", Map.of("before", "DRAFT", "after", "SUBMITTED")), "submitted",
                "127.0.0.1", Instant.parse("2026-09-01T10:00:00Z"));
    }
}
