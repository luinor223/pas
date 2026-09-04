package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByOwnerTypeAndOwnerId(EntityType ownerType, UUID ownerId);

    boolean existsByOwnerTypeAndOwnerId(EntityType ownerType, UUID ownerId);

    @Query("select distinct a.ownerId from Attachment a "
            + "where a.ownerType = :ownerType and a.ownerId in :ownerIds")
    List<UUID> findOwnerIdsWithAttachments(@Param("ownerType") EntityType ownerType,
                                           @Param("ownerIds") Collection<UUID> ownerIds);

    @Query("select a.storagePath from Attachment a where a.storagePath in :paths")
    List<String> findStoragePathsIn(@Param("paths") Collection<String> paths);
}
