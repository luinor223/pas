package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByCode(String code);

    boolean existsByCode(String code);

    @Query("""
            select c from Customer c
            where (:q is null or lower(c.name) like lower(concat('%', :q, '%'))
                              or lower(c.code) like lower(concat('%', :q, '%')))
              and (:status is null or c.status = :status)
            """)
    Page<Customer> search(@Param("q") String q, @Param("status") CustomerStatus status, Pageable pageable);
}
