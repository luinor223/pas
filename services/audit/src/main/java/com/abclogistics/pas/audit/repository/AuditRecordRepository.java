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

/**
 * INSERT + SELECT only — the grants say so, and so does this interface.
 *
 * <p>It deliberately extends {@link Repository}, the marker interface, rather than
 * {@code JpaRepository}: the convenient base ships {@code save}, {@code saveAll}, {@code delete},
 * {@code deleteAll} and {@code deleteById}, and inheriting them would hand every caller in this
 * service the ability to rewrite or erase the trail. The whole argument for centralizing audit
 * (D15) is that a service can no longer edit its own history, so the surface here is enumerated,
 * not inherited. {@code AuditImmutabilityTest} asserts the exact public surface — including
 * inherited methods, which an earlier version of that test could not see.
 */
public interface AuditRecordRepository extends Repository<AuditRecord, UUID> {

    /**
     * The ingest path. Native because the dedup is {@code ON CONFLICT DO NOTHING} on the primary
     * key, which JPA has no vocabulary for: {@code save} would issue a SELECT-then-UPDATE and let a
     * replayed record overwrite the original. {@code id} is the producer's outbox row id, i.e. the
     * envelope {@code event_id} — which is why this service needs no {@code processed_event}.
     *
     * @return 1 when the row was inserted, 0 when this event had already been recorded
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
