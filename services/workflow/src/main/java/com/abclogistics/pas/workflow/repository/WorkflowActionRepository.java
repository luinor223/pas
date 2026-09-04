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
    java.util.Optional<WorkflowAction> findByIdempotencyKey(UUID idempotencyKey);
    List<WorkflowAction> findByStepInstance_IdOrderByCreatedAtAsc(UUID stepInstanceId);
    boolean existsByStepInstance_Instance_Id(UUID instanceId);

    @Query(value = """
            select a from WorkflowAction a
            join fetch a.stepInstance si join fetch si.instance wi
            where a.actorId = :userId
            """ + InboxQueryFilters.COMMON + """
            order by a.createdAt desc, a.id desc
            """, countQuery = """
            select count(a) from WorkflowAction a
            join a.stepInstance si join si.instance wi
            where a.actorId = :userId
            """ + InboxQueryFilters.COMMON)
    Page<WorkflowAction> findCompletedInboxPage(
            @Param("userId") UUID userId, @Param("q") String q,
            @Param("documentType") String documentType, @Param("priority") String priority,
            Pageable pageable);
}
