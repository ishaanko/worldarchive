package dev.ishaankot.worldarchive.model;

import java.util.Objects;

/** Catalog entry joining a portable manifest to its preserved aggregate result. */
public record BackupRecord(BackupManifest manifest, BackupResult result) {
    public BackupRecord {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(result, "result");
        if (!manifest.backupId().equals(result.backupId())) {
            throw new IllegalArgumentException("Manifest and result backup IDs differ");
        }
        if (!manifest.worldId().equals(result.worldId())) {
            throw new IllegalArgumentException("Manifest and result world IDs differ");
        }
        if (result.completedAt().isBefore(manifest.createdAt())) {
            throw new IllegalArgumentException("Backup cannot complete before it was created");
        }
    }
}
