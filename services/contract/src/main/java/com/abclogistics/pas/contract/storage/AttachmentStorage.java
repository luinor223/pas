package com.abclogistics.pas.contract.storage;

import com.abclogistics.pas.contract.domain.EntityType;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Storage port for attachment bytes. Filesystem today, S3 without touching callers. */
public interface AttachmentStorage {

    String store(EntityType ownerType, UUID objectId, InputStream content, long size) throws IOException;

    Resource load(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;

    List<StoredObject> findOlderThan(Instant cutoff);

    record StoredObject(String storageKey) { }
}
