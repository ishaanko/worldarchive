package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.ImportSourceId;
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
