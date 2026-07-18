package dev.ishaankot.worldarchive.model;

import java.util.Objects;
import java.util.Optional;

/** Versioned identity persisted inside a world, including restored-copy provenance. */
public record WorldIdentity(
        int schemaVersion,
        WorldId worldId,
        Optional<BackupId> sourceBackupId) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorldIdentity {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported world identity schema: " + schemaVersion);
        }
        Objects.requireNonNull(worldId, "worldId");
        sourceBackupId = Objects.requireNonNull(sourceBackupId, "sourceBackupId");
    }

    public static WorldIdentity original(WorldId worldId) {
        return new WorldIdentity(CURRENT_SCHEMA_VERSION, worldId, Optional.empty());
    }

    public static WorldIdentity restoredCopy(WorldId worldId, BackupId sourceBackupId) {
        return new WorldIdentity(CURRENT_SCHEMA_VERSION, worldId, Optional.of(sourceBackupId));
    }
}
