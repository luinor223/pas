package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.DocumentCounter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, DocumentCounter.Key> {
}
