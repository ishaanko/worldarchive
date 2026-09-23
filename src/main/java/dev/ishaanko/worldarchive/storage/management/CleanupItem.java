package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.model.BackupId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One backup in a cleanup preview and the copies on this computer that cleanup would delete.
 * A copy on the world's remote is never touched and keeps the backup listed; a backup with no
 * copy left anywhere leaves the catalog, which {@code removesRestorePoint} warns about.
 *
 * @param gitRef the local Git snapshot to delete, if any
 * @param zipArtifactId the ZIP archive to delete, as the catalog names it, if any
 */
public record CleanupItem(
        BackupId backupId,
        Instant createdAt,
        Optional<String> label,
        long changedFileCount,
        Optional<String> gitRef,
        Optional<String> zipArtifactId,
        long estimatedGitBytes,
        long exactZipBytes,
        boolean removesRestorePoint) {
    public CleanupItem {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(gitRef, "gitRef");
        Objects.requireNonNull(zipArtifactId, "zipArtifactId");
        if (changedFileCount < 0 || estimatedGitBytes < 0 || exactZipBytes < 0) {
            throw new IllegalArgumentException("Cleanup counts must not be negative");
        }
        if (gitRef.isEmpty() && zipArtifactId.isEmpty()) {
            throw new IllegalArgumentException("A cleanup item must delete at least one copy");
        }
    }

    public boolean removeGit() {
        return gitRef.isPresent();
    }

    public boolean removeZip() {
        return zipArtifactId.isPresent();
    }

    public long estimatedReclaimableBytes() {
        return Math.addExact(estimatedGitBytes, exactZipBytes);
    }
}
