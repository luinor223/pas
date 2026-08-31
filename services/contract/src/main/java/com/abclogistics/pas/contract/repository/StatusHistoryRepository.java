package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately <b>not</b> a {@code JpaRepository} (D17 append-only): {@code updatable = false}
 * stops a row being rewritten, not removed, and JpaRepository hands every caller {@code deleteAll}.
 * The surface is declared so adding to it is a deliberate act; a reflection test pins the absence.
 */
public interface StatusHistoryRepository extends Repository<StatusHistory, UUID> {

    StatusHistory save(StatusHistory row);

    List<StatusHistory> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(EntityType entityType,
                                                                       UUID entityId);

    /** Reads only — tests assert "no new row" globally rather than per document. */
    long count();
}
