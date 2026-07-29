package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Persists weekly suppression for non-modal storage review notices. */
public final class FileStorageReviewStore {
    private static final Duration CADENCE = Duration.ofDays(7);

    private static final int MAXIMUM_FILE_BYTES = 128;

    private final Path directory;

    public FileStorageReviewStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
    }

    public synchronized boolean claimIfDue(WorldId worldId, Instant now)
            throws IOException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(now, "now");
        Path file = directory.resolve(worldId + ".txt").normalize();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Storage review state is not a regular file");
            }
            if (Files.size(file) > MAXIMUM_FILE_BYTES) {
                throw new IOException("Storage review state is unexpectedly large");
            }
            try {
                Instant previous = Instant.parse(
                        Files.readString(file, StandardCharsets.UTF_8).strip());
                if (now.isBefore(previous.plus(CADENCE))) {
                    return false;
                }
            } catch (DateTimeParseException exception) {
                // Replace malformed local notice state with the current safe value.
            }
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) {
            throw new IOException("Storage review state path must not be a symbolic link");
        }
        AtomicFiles.writeUtf8(file, now + System.lineSeparator());
        return true;
    }
}
