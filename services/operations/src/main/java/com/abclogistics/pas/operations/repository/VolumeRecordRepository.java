package com.abclogistics.pas.operations.repository;

import com.abclogistics.pas.operations.domain.VolumeRecord;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

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
    List<VolumeRecord> findByContractId(UUID contractId);

    @EntityGraph(attributePaths = "period")
    List<VolumeRecord> findByPeriod_Id(UUID periodId);

    long countByPeriod_Id(UUID periodId);

    interface PeriodVolumeCount {
        UUID getPeriodId();
        long getVolumeCount();
    }

    @Query("select v.period.id as periodId, count(v) as volumeCount from VolumeRecord v group by v.period.id")
    List<PeriodVolumeCount> countByPeriod();

    @Query(value = """
            select v from VolumeRecord v join fetch v.period p
            where (:periodCode is null or p.periodCode = :periodCode)
              and (:contractId is null or v.contractId = :contractId)
              and (:serviceCode is null or v.serviceCode = :serviceCode)
              and (:q is null or lower(v.recordNo) like :q or lower(v.customerName) like :q
                   or lower(v.serviceName) like :q or lower(coalesce(v.note, '')) like :q)
            order by v.updatedAt desc
            """, countQuery = """
            select count(v) from VolumeRecord v join v.period p
            where (:periodCode is null or p.periodCode = :periodCode)
              and (:contractId is null or v.contractId = :contractId)
              and (:serviceCode is null or v.serviceCode = :serviceCode)
              and (:q is null or lower(v.recordNo) like :q or lower(v.customerName) like :q
                   or lower(v.serviceName) like :q or lower(coalesce(v.note, '')) like :q)
            """)
    Page<VolumeRecord> searchPage(@Param("periodCode") String periodCode,
                                  @Param("contractId") UUID contractId,
                                  @Param("serviceCode") String serviceCode,
                                  @Param("q") String q,
                                  Pageable pageable);

    @Query(value = "SELECT nextval('operations.volume_record_no_seq')", nativeQuery = true)
    Long nextRecordNo();
}
