package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.WorkflowDefinition;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    List<WorkflowDefinition> findByDocumentType_Code(String documentTypeCode);

    Optional<WorkflowDefinition> findByDocumentType_CodeAndActiveTrue(String documentTypeCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WorkflowDefinition d where d.id = :id")
    Optional<WorkflowDefinition> findWithLockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WorkflowDefinition d where d.documentType.id = :docTypeId and d.active = true")
    List<WorkflowDefinition> findActiveWithLockByDocumentTypeId(@Param("docTypeId") UUID docTypeId);

    @Query("select coalesce(max(d.versionNo), 0) from WorkflowDefinition d where d.documentType.id = :docTypeId")
    int maxVersionNoByDocumentTypeId(@Param("docTypeId") UUID docTypeId);
}
