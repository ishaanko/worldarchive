package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Objects;

/**
 * A delete the player confirmed: the backup, its world, and the copies the player saw listed for
 * it. The delete goes ahead only while the backup list still names exactly these copies, checked
 * inside the world's gate; a backup that changed in the meantime is left alone.
 */
public record DeleteBackupRequest(WorldId worldId, BackupId backupId, List<DestinationResult> copies) {
    public DeleteBackupRequest {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        copies = List.copyOf(copies);
        if (copies.stream().noneMatch(DestinationResult::isDurable)) {
            throw new IllegalArgumentException("A delete must name at least one copy of the backup");
        }
    }
}
