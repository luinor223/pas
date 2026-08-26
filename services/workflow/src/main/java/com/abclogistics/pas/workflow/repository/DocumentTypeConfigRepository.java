package com.abclogistics.pas.workflow.repository;

import com.abclogistics.pas.workflow.domain.DocumentTypeConfig;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentTypeConfigRepository extends JpaRepository<DocumentTypeConfig, UUID> {
    Optional<DocumentTypeConfig> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DocumentTypeConfig d where d.id = :id")
    Optional<DocumentTypeConfig> findWithLockById(@Param("id") UUID id);
}
