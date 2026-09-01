package com.abclogistics.pas.operations.repository;

import com.abclogistics.pas.operations.domain.VolumeRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VolumeRecordRepository extends JpaRepository<VolumeRecord, Long> {

    @Query("SELECT vr FROM VolumeRecord vr WHERE vr.periodCode = :periodCode ORDER BY vr.contractCode, vr.serviceCode")
    Page<VolumeRecord> findByPeriodCode(@Param("periodCode") String periodCode, Pageable pageable);

    @Query("SELECT vr FROM VolumeRecord vr WHERE vr.periodCode = :periodCode AND vr.contractCode = :contractCode ORDER BY vr.serviceCode")
    List<VolumeRecord> findByPeriodCodeAndContractCode(@Param("periodCode") String periodCode, @Param("contractCode") String contractCode);

    @Query("SELECT vr FROM VolumeRecord vr WHERE vr.periodCode = :periodCode AND vr.contractId = :contractId ORDER BY vr.serviceCode")
    List<VolumeRecord> findByPeriodCodeAndContractId(@Param("periodCode") String periodCode, @Param("contractId") Long contractId);

    Optional<VolumeRecord> findByIdAndPeriodCode(Long id, String periodCode);

    long countByPeriodCode(String periodCode);
}
