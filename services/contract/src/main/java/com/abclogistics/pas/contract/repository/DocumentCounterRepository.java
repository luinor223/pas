package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.DocumentCounter;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to the {@code CTR-}/{@code ADD-} counters. Allocation does NOT go through this
 * repository — it is a single atomic upsert in {@code DocumentNumberService}, because a
 * select-for-update cannot lock a row that does not exist yet.
 */
public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, DocumentCounter.Key> {
}
