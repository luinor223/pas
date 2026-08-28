package com.abclogistics.pas.contract.storage;

import com.abclogistics.pas.contract.domain.EntityType;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Storage port for attachment bytes.
 *
 * <p>The contract domain owns metadata and transaction reconciliation; an implementation owns
 * where and how the bytes are stored. The current implementation uses a mounted filesystem, while
 * a future MinIO/S3 implementation can satisfy the same operations without changing
 * {@code AttachmentService}.
 */
public interface AttachmentStorage {

    /** Stores the bytes and returns the opaque key persisted with the attachment metadata. */
    String store(EntityType ownerType, UUID objectId, InputStream content, long size) throws IOException;

    /** Resolves a stored object into a Spring resource suitable for download streaming. */
    Resource load(String storageKey) throws IOException;

    /** Deletes the object if present. */
    void delete(String storageKey) throws IOException;

    /** Objects old enough to be considered by the orphan-reconciliation sweep. */
    List<StoredObject> findOlderThan(Instant cutoff);

    record StoredObject(String storageKey) { }
}
