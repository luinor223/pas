package com.abclogistics.pas.contract.storage;

import com.abclogistics.pas.contract.domain.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/** Mounted-volume implementation of {@link AttachmentStorage}. */
@Component
public class FileSystemAttachmentStorage implements AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(FileSystemAttachmentStorage.class);

    private final Path storageRoot;

    public FileSystemAttachmentStorage(
            @Value("${contract.attachment-storage-path}") String storagePath) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public String store(EntityType ownerType, UUID objectId, InputStream content, long size)
            throws IOException {
        Path destination = storageRoot
                .resolve(ownerType.name().toLowerCase(Locale.ROOT))
                .resolve(objectId.toString())
                .toAbsolutePath()
                .normalize();
        requireContained(destination);
        Files.createDirectories(destination.getParent());
        Files.copy(content, destination);
        return destination.toString();
    }

    @Override
    public Resource load(String storageKey) {
        return new FileSystemResource(containedPath(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(containedPath(storageKey));
    }

    @Override
    public List<StoredObject> findOlderThan(Instant cutoff) {
        if (!Files.isDirectory(storageRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(storageRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> lastModified(path).isBefore(cutoff))
                    .map(path -> new StoredObject(path.toAbsolutePath().normalize().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not read the attachment storage root {}", storageRoot, e);
            return List.of();
        }
    }

    private Path containedPath(String storageKey) {
        Path path = Path.of(storageKey).toAbsolutePath().normalize();
        requireContained(path);
        return path;
    }

    private void requireContained(Path path) {
        if (!path.startsWith(storageRoot)) {
            throw new IllegalStateException(
                    "Attachment storage key points outside the storage root: " + path);
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            // An unreadable timestamp is treated as brand new so reconciliation leaves it alone.
            return Instant.now();
        }
    }
}
