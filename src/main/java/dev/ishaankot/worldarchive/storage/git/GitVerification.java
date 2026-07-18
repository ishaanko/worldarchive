package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;
import java.util.Optional;

/** Git and LFS integrity result for one exact snapshot. */
public record GitVerification(
        GitSnapshot snapshot,
        Optional<BackupManifest> manifest,
        boolean valid,
        String message) {
    public GitVerification {
        Objects.requireNonNull(snapshot, "snapshot");
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (valid && manifest.isEmpty()) {
            throw new IllegalArgumentException("A valid Git verification must expose its manifest");
        }
        Objects.requireNonNull(message, "message");
        message = SensitiveDataRedactor.redact(message);
        if (message.isBlank() || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Verification message must be safe display text");
        }
    }

    /** Compatibility constructor for a verification that cannot expose a trusted manifest. */
    public GitVerification(GitSnapshot snapshot, boolean valid, String message) {
        this(snapshot, Optional.empty(), valid, message);
    }
}
