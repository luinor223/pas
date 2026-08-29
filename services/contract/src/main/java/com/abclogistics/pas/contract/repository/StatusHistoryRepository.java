package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately <b>not</b> a {@code JpaRepository} (D17 append-only).
 *
 * <p>{@code updatable = false} on every column of {@link StatusHistory} stops a row being
 * rewritten, but says nothing about it being removed — and {@code JpaRepository} hands every
 * caller {@code delete}, {@code deleteAll}, {@code deleteAllInBatch}. A transition log that any
 * service can delete from is not a log: the status column is only cross-checkable against it
 * because the log is complete, and one {@code deleteAll} makes a document's history quietly agree
 * with whatever its status column happens to say.
 *
 * <p>So the surface is declared, not inherited. Adding a method here is a deliberate act; the
 * absence of a delete is enforced by a reflection test, not by convention.
 */
public interface StatusHistoryRepository extends Repository<StatusHistory, UUID> {

    StatusHistory save(StatusHistory row);

    List<StatusHistory> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(EntityType entityType,
                                                                       UUID entityId);

    /** Reads only — several tests assert "no new row" globally rather than per document. */
    long count();
}
