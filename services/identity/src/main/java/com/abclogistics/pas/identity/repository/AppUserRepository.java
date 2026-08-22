package com.abclogistics.pas.identity.repository;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.UserStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @EntityGraph(attributePaths = {"roles", "department"})
    Optional<AppUser> findByUsername(String username);

    @EntityGraph(attributePaths = {"roles", "department"})
    Optional<AppUser> findWithGraphById(UUID id);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    List<AppUser> findByRoles_CodeAndStatus(String roleCode, UserStatus status);
}
