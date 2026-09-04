package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.SigningRequestGuard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SigningRequestGuardRepository
        extends JpaRepository<SigningRequestGuard, SigningRequestGuard.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from SigningRequestGuard g where g.documentType = :documentType and g.documentId = :documentId")
    Optional<SigningRequestGuard> findForUpdate(@Param("documentType") String documentType,
                                                @Param("documentId") UUID documentId);
}
