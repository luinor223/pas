package com.abclogistics.pas.identity.repository;

import com.abclogistics.pas.identity.domain.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findByCode(String code);

    /** Row lock on the role for the permission-set replace, so concurrent replaces serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Role> findWithLockByCode(String code);
}
