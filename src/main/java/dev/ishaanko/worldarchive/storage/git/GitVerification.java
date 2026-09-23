package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.SafeText;
import java.util.Objects;
import java.util.Optional;

/** The result of checking one snapshot: valid snapshots expose the manifest they hold. */
public record GitVerification(Optional<BackupManifest> manifest, boolean valid, String message) {
    public GitVerification {
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (valid && manifest.isEmpty()) {
            throw new IllegalArgumentException("A valid Git verification must expose its manifest");
        }
        message = SafeText.of(Objects.requireNonNull(message, "message"), "Git verification finished", 1_024);
    }

    static GitVerification verified(BackupManifest manifest) {
        return new GitVerification(Optional.of(manifest), true, "Git and Git LFS objects verified");
    }

    static GitVerification failed(String message) {
        return new GitVerification(Optional.empty(), false, message);
    }
}
