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

/** Deletes stored files no attachment row references. Never the reverse: the row is the truth. */
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

    public int removeOrphans() {
        // younger than the grace period may be an upload whose transaction has not committed yet
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
                // still no row pointing at it, so the next sweep sees it again
                log.warn("Failed to delete orphaned attachment object {}", object.storageKey(), e);
            }
        }
        return deleted;
    }
}
