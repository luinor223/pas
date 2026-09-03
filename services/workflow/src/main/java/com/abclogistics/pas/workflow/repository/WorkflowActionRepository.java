package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.WorkflowAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WorkflowActionRepository extends JpaRepository<WorkflowAction, UUID> {
    List<WorkflowAction> findByStepInstance_IdOrderByCreatedAtAsc(UUID stepInstanceId);
    List<WorkflowAction> findByStepInstance_Instance_IdOrderByCreatedAtAsc(UUID instanceId);
    List<WorkflowAction> findByActorIdOrderByCreatedAtDesc(UUID actorId);

    boolean existsByStepInstance_Instance_Id(UUID instanceId);

    @org.springframework.data.jpa.repository.Query("select a from WorkflowAction a join fetch a.stepInstance si join fetch si.instance where a.actorId = :actorId order by a.createdAt desc")
    List<WorkflowAction> findByActorIdWithFetchOrderByCreatedAtDesc(@org.springframework.data.repository.query.Param("actorId") UUID actorId);

    @Query(value = """
            select a from WorkflowAction a
            join fetch a.stepInstance si join fetch si.instance wi
            where a.actorId = :userId
              and (:q is null or lower(wi.documentNo) like :q or lower(coalesce(wi.customerName, '')) like :q
                   or lower(coalesce(si.name, '')) like :q or lower(coalesce(wi.requestedByName, '')) like :q)
              and (:documentType is null or wi.documentTypeCode = :documentType)
              and (:priority is null or wi.priority = :priority)
            order by a.createdAt desc
            """, countQuery = """
            select count(a) from WorkflowAction a
            join a.stepInstance si join si.instance wi
            where a.actorId = :userId
              and (:q is null or lower(wi.documentNo) like :q or lower(coalesce(wi.customerName, '')) like :q
                   or lower(coalesce(si.name, '')) like :q or lower(coalesce(wi.requestedByName, '')) like :q)
              and (:documentType is null or wi.documentTypeCode = :documentType)
              and (:priority is null or wi.priority = :priority)
            """)
    Page<WorkflowAction> findCompletedInboxPage(
            @Param("userId") UUID userId, @Param("q") String q,
            @Param("documentType") String documentType, @Param("priority") String priority,
            Pageable pageable);
}
