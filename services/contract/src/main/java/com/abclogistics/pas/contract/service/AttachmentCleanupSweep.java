package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deletes stored files that no attachment row references.
 *
 * <p>{@link AttachmentService} reconciles the bytes with the row inside a transaction
 * synchronization, which covers the ordinary cases. It cannot cover two: the process dying between
 * writing the file and committing, and a delete whose {@code Files.delete} fails after the
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
    private final Path storageRoot;
    private final Duration grace;
    private final boolean enabled;

    public AttachmentCleanupSweep(AttachmentRepository attachments,
                                  @Value("${contract.attachment-storage-path}") String storagePath,
                                  @Value("${contract.attachment-cleanup-grace}") Duration grace,
                                  @Value("${contract.attachment-cleanup-enabled:true}") boolean enabled) {
        this.attachments = attachments;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
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
        if (!Files.isDirectory(storageRoot)) {
            return 0;
        }
        // Anything younger than the grace period may belong to an upload whose transaction has not
        // committed yet. Deleting it would destroy a file its own row is about to point at.
        Instant cutoff = Instant.now().minus(grace);
        List<Path> candidates = filesOlderThan(cutoff);
        if (candidates.isEmpty()) {
            return 0;
        }

        Set<String> referenced = new HashSet<>(attachments.findStoragePathsIn(
                candidates.stream().map(Path::toString).toList()));

        int deleted = 0;
        for (Path file : candidates) {
            if (referenced.contains(file.toString())) {
                continue;
            }
            try {
                Files.deleteIfExists(file);
                deleted++;
                log.info("Deleted orphaned attachment file {}", file);
            } catch (IOException e) {
                // Still no row pointing at it, so the next sweep sees it again.
                log.warn("Failed to delete orphaned attachment file {}", file, e);
            }
        }
        return deleted;
    }

    private List<Path> filesOlderThan(Instant cutoff) {
        try (Stream<Path> walk = Files.walk(storageRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> lastModified(path).isBefore(cutoff))
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not read the attachment storage root {}", storageRoot, e);
            return List.of();
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            // Unreadable timestamp: treat it as brand new so the sweep leaves it alone.
            return Instant.now();
        }
    }
}
