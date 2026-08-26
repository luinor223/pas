package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.WorkflowStepInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStepInstanceRepository extends JpaRepository<WorkflowStepInstance, UUID> {

    List<WorkflowStepInstance> findByInstance_IdOrderByStepOrderAsc(UUID instanceId);

    Optional<WorkflowStepInstance> findByInstance_IdAndStepOrder(UUID instanceId, int stepOrder);

    @Query("select s from WorkflowStepInstance s where s.instance.id = :instanceId and s.status = :status")
    List<WorkflowStepInstance> findByInstanceIdAndStatus(@Param("instanceId") UUID instanceId, @Param("status") String status);

    // D5 optimistic-lock: update only if version and status ACTIVE match
    @Modifying
    @Query("update WorkflowStepInstance s set s.status = :newStatus, s.completedAt = :now, s.actedBy = :actorId, s.actedByName = :actorName, s.version = s.version + 1 where s.id = :id and s.version = :expectedVersion and s.status = 'ACTIVE'")
    int approveIfActive(@Param("id") UUID id, @Param("expectedVersion") int expectedVersion,
                        @Param("newStatus") String newStatus, @Param("now") Instant now,
                        @Param("actorId") UUID actorId, @Param("actorName") String actorName);

    @Query("select s from WorkflowStepInstance s where s.instance.id = :instanceId and s.stepOrder = :order")
    Optional<WorkflowStepInstance> findByInstanceAndOrder(@Param("instanceId") UUID instanceId, @Param("order") int order);

    List<WorkflowStepInstance> findByStatusAndOverdueNotifiedAtIsNullAndActivatedAtBefore(String status, Instant before);
}
