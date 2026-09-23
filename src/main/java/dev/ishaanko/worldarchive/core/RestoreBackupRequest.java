package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import java.nio.file.Path;
import java.util.Objects;

/** Restores a backup into a newly named world copy under the supplied worlds directory. */
public record RestoreBackupRequest(
        BackupId sourceBackupId,
        Path worldsDirectory,
        String restoredWorldName) {
    /** The longest restored folder name in UTF-16 units; the Restore screen's name box stops here too. */
    public static final int MAXIMUM_NAME_LENGTH = 255;

    public RestoreBackupRequest {
        Objects.requireNonNull(sourceBackupId, "sourceBackupId");
        worldsDirectory = Objects.requireNonNull(worldsDirectory, "worldsDirectory")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(restoredWorldName, "restoredWorldName");
        if (restoredWorldName.isBlank()
                || restoredWorldName.length() > MAXIMUM_NAME_LENGTH
                || restoredWorldName.contains("/")
                || restoredWorldName.contains("\\")
                || restoredWorldName.equals(".")
                || restoredWorldName.equals("..")
                || restoredWorldName.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Restored world name is unsafe");
        }
    }
}
