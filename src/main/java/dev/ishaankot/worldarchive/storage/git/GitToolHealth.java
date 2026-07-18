package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;
import java.util.Optional;

/** Independent health results for Git and the Git LFS extension. */
public record GitToolHealth(
        boolean gitAvailable,
        boolean lfsAvailable,
        Optional<String> gitVersion,
        Optional<String> lfsVersion,
        Optional<String> gitFailure,
        Optional<String> lfsFailure) {
    public GitToolHealth {
        gitVersion = safe(gitVersion, "gitVersion");
        lfsVersion = safe(lfsVersion, "lfsVersion");
        gitFailure = safe(gitFailure, "gitFailure");
        lfsFailure = safe(lfsFailure, "lfsFailure");
        if (gitAvailable != gitVersion.isPresent() || lfsAvailable != lfsVersion.isPresent()) {
            throw new IllegalArgumentException("Available Git tools must report their versions");
        }
    }

    public boolean available() {
        return gitAvailable && lfsAvailable;
    }

    public String summary() {
        if (!gitAvailable && !lfsAvailable) {
            return "Git and Git LFS are unavailable";
        }
        if (!gitAvailable) {
            return "Git is unavailable";
        }
        if (!lfsAvailable) {
            return "Git LFS is unavailable";
        }
        return "Git and Git LFS are available";
    }

    private static Optional<String> safe(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(text -> {
            String bounded = text.length() > 2_048 ? text.substring(0, 2_048) : text;
            return SensitiveDataRedactor.redact(bounded);
        });
    }
}
