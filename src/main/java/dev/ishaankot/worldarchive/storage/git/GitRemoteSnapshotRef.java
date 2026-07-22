package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Human-readable remote branch names with compatibility for legacy UUID refs. */
final class GitRemoteSnapshotRef {
    private static final String PREFIX = "refs/heads/backups/";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd/HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    private GitRemoteSnapshotRef() {
    }

    static String current(GitSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return current(snapshot.backupId(), snapshot.committedAt());
    }

    static String current(BackupId backupId, Instant committedAt) {
        return PREFIX + TIMESTAMP.format(Objects.requireNonNull(committedAt, "committedAt"))
                + "-" + Objects.requireNonNull(backupId, "backupId");
    }

    static String searchPattern(BackupId backupId) {
        return PREFIX + "*/*-" + Objects.requireNonNull(backupId, "backupId");
    }

    static String legacy(WorldId worldId, BackupId backupId) {
        return GitSnapshot.refName(worldId, backupId);
    }
}
