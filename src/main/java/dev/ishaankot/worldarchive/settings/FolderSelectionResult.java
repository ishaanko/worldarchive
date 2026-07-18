package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.nio.file.Path;
import java.util.Objects;

/** Credential-free outcome of a platform folder picker. */
public sealed interface FolderSelectionResult {
    /** The user selected a directory. */
    record Selected(Path path) implements FolderSelectionResult {
        public Selected {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
    }

    /** The user intentionally dismissed the picker. */
    record Cancelled() implements FolderSelectionResult {
    }

    /** No native picker is available on this runtime. */
    record Unavailable(String message) implements FolderSelectionResult {
        public Unavailable {
            message = requireSafeMessage(message);
        }
    }

    /** The picker failed without changing the current setting. */
    record Failed(String message) implements FolderSelectionResult {
        public Failed {
            message = requireSafeMessage(message);
        }
    }

    private static String requireSafeMessage(String message) {
        Objects.requireNonNull(message, "message");
        String normalized = SensitiveDataRedactor.redact(message).strip();
        if (normalized.isEmpty()
                || normalized.length() > 512
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Folder picker message is invalid");
        }
        return normalized;
    }
}
