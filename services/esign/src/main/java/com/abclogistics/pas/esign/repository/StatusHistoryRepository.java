package com.abclogistics.pas.esign.repository;

import com.abclogistics.pas.esign.domain.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {
}
