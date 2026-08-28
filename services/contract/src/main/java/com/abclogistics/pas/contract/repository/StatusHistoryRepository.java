package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {

    List<StatusHistory> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(EntityType entityType, UUID entityId);
}
