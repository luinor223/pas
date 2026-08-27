package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByOwnerTypeAndOwnerId(EntityType ownerType, UUID ownerId);

    /** CTR-02's ">= 1 attachment" submit check. */
    boolean existsByOwnerTypeAndOwnerId(EntityType ownerType, UUID ownerId);
}
