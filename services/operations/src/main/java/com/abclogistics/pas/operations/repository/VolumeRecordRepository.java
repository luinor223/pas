package com.abclogistics.pas.operations.repository;

import com.abclogistics.pas.operations.domain.VolumeRecord;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface VolumeRecordRepository extends JpaRepository<VolumeRecord, UUID> {

    @EntityGraph(attributePaths = "period")
    @Override
    List<VolumeRecord> findAll();

    @EntityGraph(attributePaths = "period")
    List<VolumeRecord> findByPeriod_PeriodCode(String periodCode);

    @EntityGraph(attributePaths = "period")
    List<VolumeRecord> findByContractIdAndPeriod_PeriodCode(UUID contractId, String periodCode);

    @EntityGraph(attributePaths = "period")
    List<VolumeRecord> findByPeriod_Id(UUID periodId);

    @Query(value = "SELECT nextval('operations.volume_record_no_seq')", nativeQuery = true)
    Long nextRecordNo();
}
