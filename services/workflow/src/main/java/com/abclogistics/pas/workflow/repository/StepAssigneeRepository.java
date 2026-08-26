package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.StepAssignee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StepAssigneeRepository extends JpaRepository<StepAssignee, UUID> {
    List<StepAssignee> findByStepInstance_Id(UUID stepInstanceId);
    List<StepAssignee> findByStepInstance_Instance_Id(UUID instanceId);
    List<StepAssignee> findByUserId(UUID userId);

    @Query("select sa.stepInstance.id from StepAssignee sa where sa.userId = :userId and sa.stepInstance.status = 'ACTIVE'")
    List<UUID> findActiveStepIdsByUserId(@Param("userId") UUID userId);

    boolean existsByStepInstance_IdAndUserId(UUID stepInstanceId, UUID userId);
}
