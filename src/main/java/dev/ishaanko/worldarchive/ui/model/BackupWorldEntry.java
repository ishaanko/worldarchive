package dev.ishaanko.worldarchive.ui.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One world in the World Backups list: a world in the saves folder, or one whose folder is gone.
 * {@code lastBackupAt} is when its newest backup was made; empty when it has none.
 */
public record BackupWorldEntry(
        BackupWorldContext context,
        boolean recoveryOnly,
        int backupCount,
        Optional<Instant> lastBackupAt) {
    public BackupWorldEntry {
        Objects.requireNonNull(context, "context");
        if (backupCount < 0) {
            throw new IllegalArgumentException("backupCount must not be negative");
        }
        Objects.requireNonNull(lastBackupAt, "lastBackupAt");
    }
}
