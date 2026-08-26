package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.WorkflowStepDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowStepDefinitionRepository extends JpaRepository<WorkflowStepDefinition, UUID> {
    List<WorkflowStepDefinition> findByDefinition_IdOrderByStepOrderAsc(UUID definitionId);
    void deleteByDefinition_Id(UUID definitionId);
}
