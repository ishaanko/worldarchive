package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;

/** Result of a restore that always points to a fresh world copy. */
public record RestoreBackupResult(
        BackupId sourceBackupId,
        WorldId restoredWorldId,
        Path restoredWorldDirectory) {
    public RestoreBackupResult {
        Objects.requireNonNull(sourceBackupId, "sourceBackupId");
        Objects.requireNonNull(restoredWorldId, "restoredWorldId");
        restoredWorldDirectory = Objects.requireNonNull(restoredWorldDirectory, "restoredWorldDirectory")
                .toAbsolutePath()
                .normalize();
    }
}
