package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;

/** Git and LFS integrity result for one exact snapshot. */
public record GitVerification(GitSnapshot snapshot, boolean valid, String message) {
    public GitVerification {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(message, "message");
        message = SensitiveDataRedactor.redact(message);
        if (message.isBlank() || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Verification message must be safe display text");
        }
    }
}
