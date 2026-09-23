package dev.ishaanko.worldarchive.model;

import java.util.Objects;
import java.util.Optional;

/**
 * The identity persisted inside a world, including where a restored copy came from. The store
 * writes {@link #CURRENT_SCHEMA_VERSION} next to it and refuses any other version.
 */
public record WorldIdentity(WorldId worldId, Optional<BackupId> sourceBackupId) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorldIdentity {
        Objects.requireNonNull(worldId, "worldId");
        sourceBackupId = Objects.requireNonNull(sourceBackupId, "sourceBackupId");
    }

    public static WorldIdentity original(WorldId worldId) {
        return new WorldIdentity(worldId, Optional.empty());
    }

    public static WorldIdentity restoredCopy(WorldId worldId, BackupId sourceBackupId) {
        return new WorldIdentity(worldId, Optional.of(sourceBackupId));
    }
}
