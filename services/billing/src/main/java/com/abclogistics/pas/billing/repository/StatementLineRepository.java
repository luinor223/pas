package com.abclogistics.pas.billing.repository;

import com.abclogistics.pas.billing.domain.StatementLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StatementLineRepository extends JpaRepository<StatementLine, UUID> {

    List<StatementLine> findByStatementIdOrderByLineNo(UUID statementId);
}
