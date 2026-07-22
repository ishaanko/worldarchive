package dev.ishaankot.worldarchive.ui;

import java.util.Objects;

/** One live or catalog-only world shown by the recovery-aware world chooser. */
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
