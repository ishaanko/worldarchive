package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import java.nio.file.Path;
import java.util.Objects;

/** Restores a backup into a newly named world copy under the supplied worlds directory. */
public record RestoreBackupRequest(
        BackupId sourceBackupId,
        Path worldsDirectory,
        String restoredWorldName) {
    public RestoreBackupRequest {
        Objects.requireNonNull(sourceBackupId, "sourceBackupId");
        worldsDirectory = Objects.requireNonNull(worldsDirectory, "worldsDirectory")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(restoredWorldName, "restoredWorldName");
        if (restoredWorldName.isBlank()
                || restoredWorldName.length() > 255
                || restoredWorldName.contains("/")
                || restoredWorldName.contains("\\")
                || restoredWorldName.equals(".")
                || restoredWorldName.equals("..")
                || restoredWorldName.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Restored world name is unsafe");
        }
    }
}
