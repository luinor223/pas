package com.abclogistics.pas.audit.service;

import com.abclogistics.pas.audit.domain.AuditRecord;
import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Read paths for entity history and admin search. */
@Service
public class AuditQueryService {

    private final AuditRecordRepository records;

    public AuditQueryService(AuditRecordRepository records) {
        this.records = records;
    }

    /** Per-entity history for the internal gRPC API. */
    @Transactional(readOnly = true)
    public Page<AuditRecord> forEntity(String entityType, UUID entityId, Pageable pageable) {
        return records.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId, pageable);
    }

    /** Cross-entity admin search. */
    @Transactional(readOnly = true)
    public Page<AuditRecord> search(String entityType, String query, UUID actorId,
                                    String sourceService, String action,
                                    Instant from, Instant to, Pageable pageable) {
        return records.search(entityType, query, actorId, sourceService, action,
                from, to, pageable);
    }
}
