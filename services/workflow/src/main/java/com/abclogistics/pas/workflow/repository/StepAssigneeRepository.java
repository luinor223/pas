package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.StepAssignee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StepAssigneeRepository extends JpaRepository<StepAssignee, UUID> {
    List<StepAssignee> findByStepInstance_Id(UUID stepInstanceId);
    @Query(value = """
            select sa from StepAssignee sa
            join fetch sa.stepInstance si join fetch si.instance wi
            where sa.userId = :userId and si.status = 'ACTIVE'
            """ + InboxQueryFilters.COMMON + """
            order by si.activatedAt asc, si.id asc
            """, countQuery = """
            select count(sa) from StepAssignee sa
            join sa.stepInstance si join si.instance wi
            where sa.userId = :userId and si.status = 'ACTIVE'
            """ + InboxQueryFilters.COMMON)
    Page<StepAssignee> findInboxPage(@Param("userId") UUID userId, @Param("q") String q,
                                     @Param("documentType") String documentType,
                                     @Param("priority") String priority, Pageable pageable);

    boolean existsByStepInstance_IdAndUserId(UUID stepInstanceId, UUID userId);
}
