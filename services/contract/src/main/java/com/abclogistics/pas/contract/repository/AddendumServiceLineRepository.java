package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.AddendumServiceLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AddendumServiceLineRepository extends JpaRepository<AddendumServiceLine, UUID> {

    List<AddendumServiceLine> findByAddendumId(UUID addendumId);
}
