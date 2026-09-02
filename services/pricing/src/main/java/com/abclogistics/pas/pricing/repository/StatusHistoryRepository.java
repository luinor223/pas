package com.abclogistics.pas.pricing.repository;

import com.abclogistics.pas.pricing.domain.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {
    List<StatusHistory> findByVersionIdOrderByCreatedAt(UUID versionId);
}
