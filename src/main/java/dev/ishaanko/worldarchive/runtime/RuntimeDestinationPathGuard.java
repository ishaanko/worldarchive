package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Objects;

/**
 * Refuses a settings save that would move a backup folder away from the backups the catalog lists
 * in it: WorldArchive would no longer find them. Folders without listed backups may move freely.
 */
final class RuntimeDestinationPathGuard {
    private RuntimeDestinationPathGuard() {
    }

    /** Throws when {@code replacement} moves a folder that holds a copy one of {@code records} lists. */
    static void requireAllowed(
            RuntimeStoragePaths current,
            RuntimeStoragePaths replacement,
            List<BackupRecord> records) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(replacement, "replacement");
        boolean gitMoves = !current.gitRepository().equals(replacement.gitRepository());
        for (BackupRecord record : records) {
            WorldId worldId = record.manifest().worldId();
            if (gitMoves && holds(record, DestinationType.GIT)) {
                throw new IllegalArgumentException("The Git folder cannot change while it holds backups that"
                        + " WorldArchive lists. Keep the folder, or delete those backups first.");
            }
            if (!current.zipDirectory(worldId).equals(replacement.zipDirectory(worldId))
                    && holds(record, DestinationType.ZIP)) {
                throw new IllegalArgumentException("A ZIP folder cannot change while it holds backups that"
                        + " WorldArchive lists. Keep the folder, or delete those backups first.");
            }
        }
    }

    private static boolean holds(BackupRecord record, DestinationType type) {
        return record.result().destinations().stream()
                .anyMatch(result -> result.destination() == type && result.isDurable());
    }
}
