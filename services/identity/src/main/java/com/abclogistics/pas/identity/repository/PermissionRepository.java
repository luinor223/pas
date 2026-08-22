package com.abclogistics.pas.identity.repository;

import com.abclogistics.pas.identity.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    List<Permission> findByCodeIn(List<String> codes);
}
