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

    /** Native insert prevents a replay from overwriting the original row. */
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

    /** Entity History tab query. */
    Page<AuditRecord> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
            String entityType, UUID entityId, Pageable pageable);

    /** Optional admin filters. Action is an exact producer action; the user-facing search matches
     * case-insensitively on a substring across a record reference and the actor's snapshot name. */
    @Query("""
            select r from AuditRecord r
            where r.sourceService = coalesce(:sourceService, r.sourceService)
              and r.occurredAt   >= coalesce(:from, r.occurredAt)
              and r.occurredAt   <= coalesce(:to, r.occurredAt)
              and (upper(r.entityType) = upper(:entityType) or :entityType is null)
              and (r.action = :action or :action is null)
              and ((lower(r.entityNo) like lower(concat('%', :query, '%'))
                    or lower(r.actorName) like lower(concat('%', :query, '%')))
                   or :query is null)
              and (r.actorId  = :actorId  or cast(:actorId as java.util.UUID) is null)
            order by r.occurredAt desc
            """)
    Page<AuditRecord> search(@Param("entityType") String entityType,
                             @Param("query") String query,
                             @Param("actorId") UUID actorId,
                             @Param("sourceService") String sourceService,
                             @Param("action") String action,
                             @Param("from") Instant from,
                             @Param("to") Instant to,
                             Pageable pageable);

    Optional<AuditRecord> findById(UUID id);

    long count();
}
