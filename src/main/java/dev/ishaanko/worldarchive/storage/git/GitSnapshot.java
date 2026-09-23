package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.Objects;

/**
 * One backup's snapshot in its world's repository: the ref that names it, the commit it points
 * at, and that commit's time. New snapshot commits have no parent; imported and older ones may.
 */
public record GitSnapshot(
        WorldId worldId,
        BackupId backupId,
        String refName,
        String commitId,
        Instant committedAt) {
    public GitSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(committedAt, "committedAt");
        if (!refName.equals(refName(worldId, backupId))) {
            throw new IllegalArgumentException("Snapshot ref does not match its identities");
        }
        if (!GitRepository.isObjectId(commitId)) {
            throw new IllegalArgumentException("Snapshot commit is not a SHA-1 object ID");
        }
    }

    public static String refName(WorldId worldId, BackupId backupId) {
        return "refs/heads/worldarchive/" + Objects.requireNonNull(worldId, "worldId")
                + "/" + Objects.requireNonNull(backupId, "backupId");
    }
}
