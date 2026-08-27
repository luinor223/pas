package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * INSERT and SELECT only (D17). No update or delete method belongs here, and none may be added:
 * the append-only guarantee is what makes the status column cross-checkable against this log.
 */
public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {

    List<StatusHistory> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(EntityType entityType, UUID entityId);
}
