package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    Optional<WorkflowInstance> findByIdempotencyKey(UUID idempotencyKey);

    Optional<WorkflowInstance> findByDocumentTypeCodeAndDocumentIdAndStatus(String documentTypeCode, UUID documentId, String status);

    @Query("select wi from WorkflowInstance wi where wi.documentTypeCode = :docType and wi.documentId = :docId and wi.status = 'IN_PROGRESS'")
    Optional<WorkflowInstance> findInProgressByDocument(@Param("docType") String docType, @Param("docId") UUID docId);

    @Query("select wi from WorkflowInstance wi where wi.documentTypeCode = :docType and wi.documentId = :docId order by wi.createdAt desc")
    List<WorkflowInstance> findByDocumentOrderByCreatedAtDesc(@Param("docType") String docType, @Param("docId") UUID docId);

    List<WorkflowInstance> findByRequestedBy(UUID requestedBy);

    interface InstanceWithCurrentStep {
        WorkflowInstance getInstance();
        WorkflowStepInstance getStep();
    }

    @Query(value = """
            select wi as instance, si as step from WorkflowInstance wi
            left join WorkflowStepInstance si on si.instance = wi and si.stepOrder = wi.currentStepOrder
            where wi.requestedBy = :userId
              and (:q is null or lower(wi.documentNo) like :q or lower(coalesce(wi.customerName, '')) like :q
                   or lower(coalesce(si.name, '')) like :q or lower(coalesce(wi.requestedByName, '')) like :q)
              and (:documentType is null or wi.documentTypeCode = :documentType)
              and (:priority is null or wi.priority = :priority)
            order by wi.createdAt desc
            """, countQuery = """
            select count(wi) from WorkflowInstance wi
            left join WorkflowStepInstance si on si.instance = wi and si.stepOrder = wi.currentStepOrder
            where wi.requestedBy = :userId
              and (:q is null or lower(wi.documentNo) like :q or lower(coalesce(wi.customerName, '')) like :q
                   or lower(coalesce(si.name, '')) like :q or lower(coalesce(wi.requestedByName, '')) like :q)
              and (:documentType is null or wi.documentTypeCode = :documentType)
              and (:priority is null or wi.priority = :priority)
            """)
    Page<InstanceWithCurrentStep> findSubmittedInboxPage(
            @Param("userId") UUID userId, @Param("q") String q,
            @Param("documentType") String documentType, @Param("priority") String priority,
            Pageable pageable);

    @Query("select wi from WorkflowInstance wi where wi.status = 'IN_PROGRESS' and wi.currentStepOrder is not null")
    List<WorkflowInstance> findAllInProgress();
}
