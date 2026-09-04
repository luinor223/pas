package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import com.abclogistics.pas.audit.service.AuditIngestService;
import com.abclogistics.pas.common.audit.AuditPayload;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A producer defect cannot repair itself by being retried, so it has to be named here rather
 * than left to the source_service CHECK constraint four minutes of back-off later.
 */
class AuditIngestValidationTest {

    private AuditRecordRepository records;
    private AuditIngestService ingest;

    @BeforeEach
    void setUp() {
        records = mock(AuditRecordRepository.class);
        ingest = new AuditIngestService(records, AuditEventFixtures.MAPPER);
    }

    @Test
    void aBlankEntityTypeIsMalformed() {
        assertThatThrownBy(() -> ingest(payload("contract-service", "   ", "UPDATE")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasInserted();
    }

    @Test
    void aBlankActionIsMalformed() {
        assertThatThrownBy(() -> ingest(payload("contract-service", "CONTRACT", "")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasInserted();
    }

    @Test
    void aBlankSourceServiceIsMalformed() {
        assertThatThrownBy(() -> ingest(payload("  ", "CONTRACT", "UPDATE")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasInserted();
    }

    @Test
    void aSourceServiceOutsideTheProducerSetIsMalformedNotTransient() {
        // reaching the CHECK constraint would make a permanent defect look like a flaky insert
        assertThatThrownBy(() -> ingest(payload("reporting-service", "CONTRACT", "UPDATE")))
                .isInstanceOf(MalformedEventException.class);
        nothingWasInserted();
    }

    @Test
    void everyDocumentedProducerIsAccepted() {
        when(records.insertIgnoringDuplicate(any(), anyString(), anyString(), any(), any(),
                anyString(), any(), any(), any(), any(), any(), anyString(), any(), any(), any()))
                .thenReturn(1);

        for (String producer : new String[] {"identity-service", "contract-service",
                "pricing-service", "operations-service", "billing-service", "workflow-service",
                "esign-service"}) {
            assertThat(ingest(payload(producer, "CONTRACT", "UPDATE"))).as(producer).isTrue();
        }
    }

    private boolean ingest(AuditPayload payload) {
        return ingest.ingest(UUID.randomUUID(), AuditEventFixtures.MAPPER.writeValueAsString(payload));
    }

    private void nothingWasInserted() {
        verify(records, never()).insertIgnoringDuplicate(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static AuditPayload payload(String sourceService, String entityType, String action) {
        return new AuditPayload(sourceService, entityType, UUID.randomUUID(), "HD-2026-0001",
                action, UUID.randomUUID(), "Nguyen Van A", "SALES", null, null, Map.of(), null,
                "10.0.0.1", Instant.parse("2026-09-01T10:00:00Z"));
    }
}
