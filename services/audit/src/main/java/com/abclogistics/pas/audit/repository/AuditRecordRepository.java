package com.abclogistics.pas.audit.repository;

import com.abclogistics.pas.audit.domain.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * INSERT + SELECT only. No {@code save} of an existing row, no delete path — the grants say so and
 * so does this interface (`AuditImmutabilityTest` asserts it).
 */
public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {

    /** The History tab's hot path — {@code (entity_type, entity_id, occurred_at DESC)}. */
    Page<AuditRecord> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
            String entityType, UUID entityId, Pageable pageable);

    /** The admin cross-entity search. Every filter is optional and ANDed. */
    @Query("""
            select r from AuditRecord r
            where (:entityType is null or r.entityType = :entityType)
              and (:entityNo   is null or r.entityNo   = :entityNo)
              and (:actorId    is null or r.actorId    = :actorId)
              and (:sourceService is null or r.sourceService = :sourceService)
              and (:action     is null or r.action     = :action)
              and (:from       is null or r.occurredAt >= :from)
              and (:to         is null or r.occurredAt <= :to)
            order by r.occurredAt desc
            """)
    Page<AuditRecord> search(@Param("entityType") String entityType,
                             @Param("entityNo") String entityNo,
                             @Param("actorId") UUID actorId,
                             @Param("sourceService") String sourceService,
                             @Param("action") String action,
                             @Param("from") Instant from,
                             @Param("to") Instant to,
                             Pageable pageable);
}
