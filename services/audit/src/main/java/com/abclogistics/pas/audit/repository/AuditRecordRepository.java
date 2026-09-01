package com.abclogistics.pas.audit.repository;

import com.abclogistics.pas.audit.domain.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** INSERT + SELECT only — the grants say so, and so does this interface. */
public interface AuditRecordRepository extends Repository<AuditRecord, UUID> {

    /**
     * The ingest path. Native because {@code ON CONFLICT DO NOTHING} has no JPA equivalent:
     * {@code save} would SELECT-then-UPDATE and let a replay overwrite the original. @return 1
     * when the row was inserted, 0 when this event had already been recorded
     */
    @Modifying
    @Query(value = """
            insert into audit.audit_record (
                id, source_service, entity_type, entity_id, entity_no, action,
                actor_id, actor_name, actor_department, before_status, after_status,
                changes, note, ip_address, occurred_at)
            values (
                :id, :sourceService, :entityType, :entityId, :entityNo, :action,
                :actorId, :actorName, :actorDepartment, :beforeStatus, :afterStatus,
                cast(:changes as jsonb), :note, :ipAddress, :occurredAt)
            on conflict (id) do nothing
            """, nativeQuery = true)
    int insertIgnoringDuplicate(@Param("id") UUID id,
                                @Param("sourceService") String sourceService,
                                @Param("entityType") String entityType,
                                @Param("entityId") UUID entityId,
                                @Param("entityNo") String entityNo,
                                @Param("action") String action,
                                @Param("actorId") UUID actorId,
                                @Param("actorName") String actorName,
                                @Param("actorDepartment") String actorDepartment,
                                @Param("beforeStatus") String beforeStatus,
                                @Param("afterStatus") String afterStatus,
                                @Param("changes") String changes,
                                @Param("note") String note,
                                @Param("ipAddress") String ipAddress,
                                @Param("occurredAt") Instant occurredAt);

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

    Optional<AuditRecord> findById(UUID id);

    long count();
}
