package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact backup and artifact scope approved by one delete preview. */
record DeleteConfirmation(
        BackupId backupId,
        BackupManifest manifest,
        Set<ConfirmedDestination> destinations,
        Instant expiresAt) {
    DeleteConfirmation {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(manifest, "manifest");
        destinations = Set.copyOf(Objects.requireNonNull(destinations, "destinations"));
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    static DeleteConfirmation create(BackupRecord record, Instant expiresAt) {
        return new DeleteConfirmation(
                record.manifest().backupId(),
                record.manifest(),
                destinations(record),
                expiresAt);
    }

    void requireMatches(BackupRecord current) {
        if (!manifest.equals(current.manifest())
                || !destinations.equals(destinations(current))) {
            throw new BackupRecoveryException(
                    "The backup changed after deletion was confirmed");
        }
    }

    private static Set<ConfirmedDestination> destinations(BackupRecord record) {
        return record.result().destinations().stream()
                .filter(result -> result.artifactId().isPresent()
                        && (result.status() == DestinationStatus.SUCCESS
                                || result.status() == DestinationStatus.PENDING_SYNC))
                .map(ConfirmedDestination::from)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record ConfirmedDestination(
            DestinationType type,
            String artifactId,
            ArtifactOwnership ownership,
            Optional<ImportSourceId> importSourceId) {
        private static ConfirmedDestination from(DestinationResult result) {
            return new ConfirmedDestination(
                    result.destination(),
                    result.artifactId().orElseThrow(),
                    result.ownership(),
                    result.importSourceId());
        }
    }
}
