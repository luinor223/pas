package com.abclogistics.pas.billing.repository;

import com.abclogistics.pas.billing.domain.StatementLineVolume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StatementLineVolumeRepository extends JpaRepository<StatementLineVolume, Long> {

    List<StatementLineVolume> findByLineId(Long lineId);
}
