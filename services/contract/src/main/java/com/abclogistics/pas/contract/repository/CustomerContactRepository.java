package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.CustomerContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerContactRepository extends JpaRepository<CustomerContact, UUID> {

    List<CustomerContact> findByCustomerId(UUID customerId);

    /** At most one exists — guaranteed by the partial unique index, not by this query. */
    Optional<CustomerContact> findByCustomerIdAndPrimaryTrue(UUID customerId);
}
