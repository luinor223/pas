package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.domain.AuditRecord;
import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import com.abclogistics.pas.audit.service.AuditQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin UC "Tra cứu audit log" (seq-02). The cross-entity axes are the reason this read path
 * had to exist beside the per-entity gRPC one: "what did user X do last week", "every reject in
 * Q3", "all actions by ACCOUNTING".
 */
class AuditRecordSearchTest {

    private AuditRecordRepository records;
    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        records = mock(AuditRecordRepository.class);
        service = new AuditQueryService(records);
        when(records.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void everyFilterIsOptional() {
        service.search(null, null, null, null, null, null, null, PageRequest.of(0, 20));

        verify(records).search(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any());
    }

    @Test
    void actorAndDateBoundTheSearchTogether() {
        // "what did user X do last week" — the two axes are ANDed, not alternatives
        UUID actor = UUID.randomUUID();
        Instant from = Instant.parse("2026-08-24T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T00:00:00Z");

        service.search(null, null, actor, null, null, from, to, PageRequest.of(0, 20));

        verify(records).search(isNull(), isNull(), eq(actor), isNull(), isNull(),
                eq(from), eq(to), any());
    }

    @Test
    void resultsAreNewestFirst() {
        AuditRecord older = record("CREATE", Instant.parse("2026-01-01T00:00:00Z"));
        AuditRecord newer = record("REJECT", Instant.parse("2026-03-01T00:00:00Z"));
        when(records.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(newer, older)));

        assertThat(service.search(null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getContent())
                .extracting(AuditRecord::getAction).containsExactly("REJECT", "CREATE");
    }

    private static AuditRecord record(String action, Instant at) {
        return AuditRecord.of(UUID.randomUUID(), "contract-service", "CONTRACT", UUID.randomUUID(),
                "HD-2026-0001", action, UUID.randomUUID(), "Nguyen Thi Lan", "SALES",
                null, null, Map.of(), null, null, at);
    }
}
