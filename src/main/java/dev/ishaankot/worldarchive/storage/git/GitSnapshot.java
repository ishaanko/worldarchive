package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** One independently addressable parentless snapshot ref. */
public record GitSnapshot(
        WorldId worldId,
        BackupId backupId,
        String refName,
        String commitId,
        Instant committedAt) {
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}");

    public GitSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(committedAt, "committedAt");
        if (!refName.equals(refName(worldId, backupId))) {
            throw new IllegalArgumentException("Snapshot ref does not match its identities");
        }
        if (!OBJECT_ID.matcher(commitId).matches()) {
            throw new IllegalArgumentException("Snapshot commit is not a SHA-1 object ID");
        }
    }

    public static String refName(WorldId worldId, BackupId backupId) {
        return "refs/heads/worldarchive/" + Objects.requireNonNull(worldId, "worldId")
                + "/" + Objects.requireNonNull(backupId, "backupId");
    }
}
