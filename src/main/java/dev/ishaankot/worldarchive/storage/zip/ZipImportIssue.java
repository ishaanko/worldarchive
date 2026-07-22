package dev.ishaankot.worldarchive.storage.zip;

import java.nio.file.Path;
import java.util.Objects;

/** Safe diagnostic for one ignored ZIP import candidate. */
public record ZipImportIssue(Path path, String message) {
    public ZipImportIssue {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ZIP import issue must contain safe text");
        }
    }
}
