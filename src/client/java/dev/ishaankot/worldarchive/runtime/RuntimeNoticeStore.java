package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.core.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Small atomic store for a warning that must survive client shutdown. */
final class RuntimeNoticeStore {
    private static final int MAXIMUM_NOTICE_BYTES = 1_024;

    private final Path file;

    RuntimeNoticeStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    synchronized Optional<String> load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)
                || Files.size(file) > MAXIMUM_NOTICE_BYTES) {
            throw new IOException("Stored background backup notice is unsafe");
        }
        return Optional.of(requireSafe(Files.readString(file, StandardCharsets.UTF_8)));
    }

    synchronized void save(String message) throws IOException {
        AtomicFiles.writeUtf8(file, requireSafe(message) + System.lineSeparator());
    }

    synchronized void retain(String message) throws IOException {
        if (load().isEmpty()) {
            save(message);
        }
    }

    synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private static String requireSafe(String message) throws IOException {
        String safe = Objects.requireNonNull(message, "message").strip();
        if (safe.isEmpty()
                || safe.length() > 512
                || safe.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("Background backup notice is invalid");
        }
        return safe;
    }
}
