package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SyncStatus;
import java.util.Optional;

/** Questions about a backup's copies that the overview, the planner and the executor share. */
final class ManagedStorageSupport {
    private ManagedStorageSupport() {
    }

    static Optional<DestinationResult> destination(BackupRecord record, DestinationType type) {
        return record.result().destinations().stream()
                .filter(result -> result.destination() == type)
                .findFirst();
    }

    /** True when the catalog lists a ZIP archive of this backup that WorldArchive owns. */
    static boolean listsOwnZip(BackupRecord record) {
        return destination(record, DestinationType.ZIP)
                .filter(result -> result.ownership() != ArtifactOwnership.EXTERNAL && result.isDurable())
                .isPresent();
    }

    /** True when this backup's Git snapshot is WorldArchive's own, not imported or linked. */
    static boolean ownGitSnapshot(BackupRecord record) {
        return destination(record, DestinationType.GIT)
                .filter(result -> result.ownership() == ArtifactOwnership.MANAGED && result.isDurable())
                .isPresent();
    }

    /**
     * True when the catalog says this backup's own Git snapshot is on the world's remote. Imported
     * snapshots are excluded: their sync status refers to the repository they came from.
     */
    static boolean synchronizedRemoteCopy(BackupRecord record) {
        return destination(record, DestinationType.GIT)
                .filter(result -> result.ownership() == ArtifactOwnership.MANAGED
                        && result.isDurable()
                        && result.syncStatus() == SyncStatus.SYNCED)
                .isPresent();
    }
}
