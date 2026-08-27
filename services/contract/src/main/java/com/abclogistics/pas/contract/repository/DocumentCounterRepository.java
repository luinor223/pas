package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.DocumentCounter;
import com.abclogistics.pas.contract.domain.EntityType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, DocumentCounter.Key> {

    /**
     * Row-locking read for number allocation. PESSIMISTIC_WRITE (SELECT … FOR UPDATE) is required,
     * not optional: two concurrent creates reading the same {@code next_seq} would both be handed
     * the same number and the second insert would fail on {@code contract_no}'s UNIQUE.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DocumentCounter c where c.docType = :docType and c.year = :year")
    Optional<DocumentCounter> lockFor(@Param("docType") EntityType docType, @Param("year") int year);
}
