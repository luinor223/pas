package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.WorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("select wi from WorkflowInstance wi where wi.status = 'IN_PROGRESS' and wi.currentStepOrder is not null")
    List<WorkflowInstance> findAllInProgress();
}
