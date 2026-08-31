package com.abclogistics.pas.audit.service;

import com.abclogistics.pas.audit.domain.AuditRecord;
import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** The two read paths (db-audit.md, seq-02). Neither substitutes for the other. */
@Service
public class AuditQueryService {

    private final AuditRecordRepository records;

    public AuditQueryService(AuditRecordRepository records) {
        this.records = records;
    }

    /** Per-entity, gRPC-facing — an owning service's History tab. */
    @Transactional(readOnly = true)
    public Page<AuditRecord> forEntity(String entityType, UUID entityId, Pageable pageable) {
        throw new UnsupportedOperationException("Phase B: per-entity history");
    }

    /** Cross-entity admin search, `audit:view_all`. */
    @Transactional(readOnly = true)
    public Page<AuditRecord> search(String entityType, String entityNo, UUID actorId,
                                    String sourceService, String action,
                                    Instant from, Instant to, Pageable pageable) {
        throw new UnsupportedOperationException("Phase B: cross-entity search");
    }
}
