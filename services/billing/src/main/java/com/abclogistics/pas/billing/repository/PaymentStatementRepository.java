package com.abclogistics.pas.billing.repository;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentStatementRepository extends JpaRepository<PaymentStatement, UUID> {

    Optional<PaymentStatement> findByStatementNo(String statementNo);

    boolean existsByStatementNo(String statementNo);

    @Query("SELECT ps FROM PaymentStatement ps ORDER BY ps.createdAt DESC")
    Page<PaymentStatement> findAllSorted(Pageable pageable);

    @Query("SELECT ps FROM PaymentStatement ps WHERE ps.contractId = :contractId ORDER BY ps.createdAt DESC")
    Page<PaymentStatement> findByContractId(@Param("contractId") UUID contractId, Pageable pageable);

    boolean existsByContractIdAndPeriodCodeAndAdjustsStatementIdIsNullAndStatusNotIn(
        UUID contractId, String periodCode, java.util.List<PaymentStatement.StatementStatus> excludedStatuses);

    // INSERT..RETURNING yields a row, so this is a selecting (not @Modifying) native query.
    @Query(value = "INSERT INTO billing.statement_no_counter(year, last_no) VALUES (:year, 1)"
        + " ON CONFLICT (year) DO UPDATE SET last_no = billing.statement_no_counter.last_no + 1"
        + " RETURNING last_no", nativeQuery = true)
    int nextStatementNoForYear(@Param("year") int year);
}
