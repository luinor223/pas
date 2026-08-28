package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.storage.AttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes stored files that no attachment row references.
 *
 * <p>{@link AttachmentService} reconciles the bytes with the row inside a transaction
 * synchronization, which covers the ordinary cases. It cannot cover two: the process dying between
 * writing the object and committing, and a storage deletion that fails after the
 * transaction has already completed. Both leave a file with no row — inert, but permanent without
 * this. The sweep is the recovery path those two paths log against.
 *
 * <p>Direction matters: it only ever deletes a file with no row, never a row with no file. The row
 * is the record of truth, so a missing file is a fault to be reported, not tidied away.
 */
@Component
public class AttachmentCleanupSweep {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupSweep.class);

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final Duration grace;
    private final boolean enabled;

    public AttachmentCleanupSweep(AttachmentRepository attachments, AttachmentStorage storage,
                                  @Value("${contract.attachment-cleanup-grace}") Duration grace,
                                  @Value("${contract.attachment-cleanup-enabled:true}") boolean enabled) {
        this.attachments = attachments;
        this.storage = storage;
        this.grace = grace;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${contract.attachment-cleanup-interval}")
    public void sweep() {
        if (enabled) {
            removeOrphans();
        }
    }

    /**
     * @return how many files were deleted — the scheduled path ignores it; tests do not.
     */
    public int removeOrphans() {
        // Anything younger than the grace period may belong to an upload whose transaction has not
        // committed yet. Deleting it would destroy a file its own row is about to point at.
        Instant cutoff = Instant.now().minus(grace);
        List<AttachmentStorage.StoredObject> candidates = storage.findOlderThan(cutoff);
        if (candidates.isEmpty()) {
            return 0;
        }

        Set<String> referenced = new HashSet<>(attachments.findStoragePathsIn(
                candidates.stream().map(AttachmentStorage.StoredObject::storageKey).toList()));

        int deleted = 0;
        for (AttachmentStorage.StoredObject object : candidates) {
            if (referenced.contains(object.storageKey())) {
                continue;
            }
            try {
                storage.delete(object.storageKey());
                deleted++;
                log.info("Deleted orphaned attachment object {}", object.storageKey());
            } catch (IOException e) {
                // Still no row pointing at it, so the next sweep sees it again.
                log.warn("Failed to delete orphaned attachment object {}", object.storageKey(), e);
            }
        }
        return deleted;
    }
}
