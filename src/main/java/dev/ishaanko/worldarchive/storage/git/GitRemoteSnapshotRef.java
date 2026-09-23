package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of backup branches on a remote. Each backup has its own branch,
 * {@code backups/<date>/<time>Z-<backup id>}, and {@code main} follows the newest backup so a
 * remote's front page shows it. Versions 0.1.0 to 0.1.3 named the branch
 * {@code worldarchive/<world id>/<backup id>}; such branches are still found and deleted.
 * Remote backups depend on these names, so they never change.
 */
final class GitRemoteSnapshotRef {
    static final String DEFAULT_BRANCH = "refs/heads/main";

    static final String PATTERN = "refs/heads/backups/*";

    private static final String PREFIX = "refs/heads/backups/";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd/HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final Pattern CURRENT = Pattern.compile(
            "refs/heads/backups/\\d{4}-\\d{2}-\\d{2}/\\d{2}-\\d{2}-\\d{2}Z-([0-9a-f-]{36})");

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

    /** The ls-remote pattern for the old branches of one world. */
    static String legacyPattern(WorldId worldId) {
        return "refs/heads/worldarchive/" + Objects.requireNonNull(worldId, "worldId") + "/*";
    }

    /** The backup a remote branch holds, under either name; empty for any other branch. */
    static Optional<BackupId> backupOf(String refName, WorldId worldId) {
        Matcher current = CURRENT.matcher(refName);
        String legacyPrefix = "refs/heads/worldarchive/" + worldId + "/";
        String backup = current.matches()
                ? current.group(1)
                : refName.startsWith(legacyPrefix) ? refName.substring(legacyPrefix.length()) : "";
        try {
            return backup.isEmpty() ? Optional.empty() : Optional.of(BackupId.parse(backup));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
