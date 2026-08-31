package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.domain.AuditRecord;
import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import com.abclogistics.pas.audit.service.AuditQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * seq-02(d) and D15/D17. A document's History tab reads <b>two</b> sources with different
 * consistency: the owning service's local {@code status_history} (synchronous) and this service's
 * {@code ListRecords} (eventual). They are not substitutes, and this half must never be the one a
 * business rule reads — so the per-entity query returns the non-status half and never claims to
 * be a status timeline.
 */
class AuditHistoryTwoSourcesTest {

    private AuditRecordRepository records;
    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        records = mock(AuditRecordRepository.class);
        service = new AuditQueryService(records);
    }

    @Test
    void perEntityHistoryIsNewestFirst() {
        UUID entityId = UUID.randomUUID();
        AuditRecord older = record(entityId, "CREATE", Instant.parse("2026-01-01T00:00:00Z"));
        AuditRecord newer = record(entityId, "UPDATE", Instant.parse("2026-02-01T00:00:00Z"));
        when(records.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                eq("CONTRACT"), eq(entityId), any()))
                .thenReturn(new PageImpl<>(List.of(newer, older)));

        Page<AuditRecord> page = service.forEntity("CONTRACT", entityId, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AuditRecord::getAction)
                .containsExactly("UPDATE", "CREATE");
    }

    @Test
    void theHistoryHalfCarriesFieldEditsThatMovedNoStatus() {
        // the reason this source exists at all: a field edit writes no status_history row, so the
        // status timeline alone would show nothing happened
        UUID entityId = UUID.randomUUID();
        AuditRecord edit = record(entityId, "UPDATE", Instant.now());
        when(records.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                eq("CONTRACT"), eq(entityId), any()))
                .thenReturn(new PageImpl<>(List.of(edit)));

        AuditRecord only = service.forEntity("CONTRACT", entityId, PageRequest.of(0, 20))
                .getContent().getFirst();

        assertThat(only.getBeforeStatus()).isNull();
        assertThat(only.getAfterStatus()).isNull();
        assertThat(only.getChanges()).isNotEmpty();
    }

    @Test
    void actorNameIsTheWriteTimeSnapshotNotAResolvedLookup() {
        // 4.10: "không phụ thuộc dữ liệu hiển thị hiện tại" — a renamed or disabled user must not
        // change what a past record shows, so nothing here calls identity
        UUID entityId = UUID.randomUUID();
        AuditRecord r = record(entityId, "UPDATE", Instant.now());
        when(records.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                eq("CONTRACT"), eq(entityId), any()))
                .thenReturn(new PageImpl<>(List.of(r)));

        assertThat(service.forEntity("CONTRACT", entityId, PageRequest.of(0, 20))
                .getContent().getFirst().getActorName())
                .isEqualTo("Nguyen Thi Lan");
    }

    private static AuditRecord record(UUID entityId, String action, Instant at) {
        return AuditRecord.of(UUID.randomUUID(), "contract-service", "CONTRACT", entityId,
                "HD-2026-0001", action, UUID.randomUUID(), "Nguyen Thi Lan", "SALES",
                null, null, Map.of("paymentTerm", Map.of("from", "30d", "to", "45d")),
                null, "10.0.0.1", at);
    }
}
