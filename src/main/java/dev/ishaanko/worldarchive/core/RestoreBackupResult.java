package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;

/** Result of a restore that always points to a fresh world copy. */
public record RestoreBackupResult(WorldId restoredWorldId, Path restoredWorldDirectory) {
    public RestoreBackupResult {
        Objects.requireNonNull(restoredWorldId, "restoredWorldId");
        restoredWorldDirectory = Objects.requireNonNull(restoredWorldDirectory, "restoredWorldDirectory")
                .toAbsolutePath()
                .normalize();
    }
}
