package dev.ishaanko.worldarchive.ui.model;

import java.util.Objects;

/** One world in the World Backups list: a world in the saves folder, or one whose folder is gone. */
public record BackupWorldEntry(
        BackupWorldContext context,
        boolean recoveryOnly,
        int backupCount) {
    public BackupWorldEntry {
        Objects.requireNonNull(context, "context");
        if (backupCount < 0) {
            throw new IllegalArgumentException("backupCount must not be negative");
        }
    }
}
