package com.abclogistics.pas.operations.repository;

import com.abclogistics.pas.operations.domain.VolumeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VolumeRecordRepository extends JpaRepository<VolumeRecord, UUID> {
    List<VolumeRecord> findByPeriod_PeriodCode(String periodCode);
    List<VolumeRecord> findByContractIdAndPeriod_PeriodCode(UUID contractId, String periodCode);
    List<VolumeRecord> findByPeriod_Id(UUID periodId);
    long countByRecordNoStartingWith(String prefix);
}
