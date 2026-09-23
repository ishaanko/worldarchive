package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import dev.ishaanko.worldarchive.model.SyncStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The copies of a backup that the player saw when confirming its delete. */
record DeleteConfirmation(Set<ConfirmedCopy> copies) {
    DeleteConfirmation {
        copies = Set.copyOf(copies);
    }

    /** What a delete request confirmed: its copies that hold the backup. */
    static DeleteConfirmation of(Collection<DestinationResult> shown) {
        return new DeleteConfirmation(shown.stream()
                .filter(DestinationResult::isDurable)
                .map(ConfirmedCopy::of)
                .collect(Collectors.toUnmodifiableSet()));
    }

    /** Whether the record still lists exactly the copies that were confirmed. */
    boolean matches(BackupRecord current) {
        return copies.equals(of(current.result().destinations()).copies());
    }

    /** The kinds of copy that were confirmed, to report a delete that did not start. */
    Set<DestinationType> copyTypes() {
        return copies.stream().map(ConfirmedCopy::type).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * One confirmed copy: which artifact, whose, and whether it was on a remote. The prompt names a
     * remote copy only for a synced backup, so a copy synced after the player confirmed is another copy.
     */
    record ConfirmedCopy(
            DestinationType type,
            String artifactId,
            ArtifactOwnership ownership,
            Optional<ImportSourceId> importSourceId,
            SyncStatus syncStatus) {
        private static ConfirmedCopy of(DestinationResult result) {
            return new ConfirmedCopy(result.destination(), result.artifactId().orElseThrow(), result.ownership(),
                    result.importSourceId(), result.syncStatus());
        }
    }
}
