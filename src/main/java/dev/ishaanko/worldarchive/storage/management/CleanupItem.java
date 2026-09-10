package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.model.BackupId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One cleanup action shown before confirmation. For a backup the keep settings do not
 * protect, {@code removeGit} deletes the Git snapshot everywhere, including the
 * configured remote, and the backup leaves the catalog. For a protected backup it drops
 * only the local Git copy; the ZIP or the verified remote copy stays.
 */
public record CleanupItem(
        BackupId backupId,
        Instant createdAt,
        Optional<String> label,
        long changedFileCount,
        boolean removeGit,
        boolean removeZip,
        Optional<String> gitRef,
        Optional<String> zipArtifactId,
        long estimatedGitBytes,
        long exactZipBytes,
        boolean removesRestorePoint) {
    public CleanupItem {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(createdAt, "createdAt");
        label = Objects.requireNonNull(label, "label");
        gitRef = Objects.requireNonNull(gitRef, "gitRef");
        zipArtifactId = Objects.requireNonNull(zipArtifactId, "zipArtifactId");
        if (changedFileCount < 0 || estimatedGitBytes < 0 || exactZipBytes < 0) {
            throw new IllegalArgumentException("Cleanup counts must not be negative");
        }
        if (!removeGit && !removeZip) {
            throw new IllegalArgumentException("Cleanup item must remove at least one local artifact");
        }
        if (removeGit != gitRef.isPresent() || removeZip != zipArtifactId.isPresent()) {
            throw new IllegalArgumentException("Cleanup artifact identities do not match their actions");
        }
    }

    public long estimatedReclaimableBytes() {
        return Math.addExact(estimatedGitBytes, exactZipBytes);
    }
}
