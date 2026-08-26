package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.WorkflowAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowActionRepository extends JpaRepository<WorkflowAction, UUID> {
    List<WorkflowAction> findByStepInstance_IdOrderByCreatedAtAsc(UUID stepInstanceId);
    List<WorkflowAction> findByStepInstance_Instance_IdOrderByCreatedAtAsc(UUID instanceId);
    List<WorkflowAction> findByActorIdOrderByCreatedAtDesc(UUID actorId);
}
