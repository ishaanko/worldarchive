package dev.ishaankot.worldarchive.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Aggregate result that retains every independent destination outcome. */
public record BackupResult(
        BackupId backupId,
        WorldId worldId,
        BackupStatus status,
        List<DestinationResult> destinations,
        Instant completedAt) {
    public BackupResult {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(status, "status");
        destinations = List.copyOf(destinations);
        if (destinations.stream().map(DestinationResult::destination).distinct().count() != destinations.size()) {
            throw new IllegalArgumentException("Each destination may appear at most once");
        }
        BackupStatus expected = aggregateStatus(destinations);
        if (status != expected) {
            throw new IllegalArgumentException("Status " + status + " does not match destination results: " + expected);
        }
        Objects.requireNonNull(completedAt, "completedAt");
    }

    public static BackupResult aggregate(
            BackupId backupId,
            WorldId worldId,
            List<DestinationResult> destinations,
            Instant completedAt) {
        List<DestinationResult> immutableDestinations = List.copyOf(destinations);
        return new BackupResult(
                backupId,
                worldId,
                aggregateStatus(immutableDestinations),
                immutableDestinations,
                completedAt);
    }

    public static BackupStatus aggregateStatus(List<DestinationResult> destinations) {
        Objects.requireNonNull(destinations, "destinations");
        boolean succeeded = destinations.stream()
                .anyMatch(result -> result.status() == DestinationStatus.SUCCESS);
        boolean failed = destinations.stream()
                .anyMatch(result -> result.status() == DestinationStatus.FAILED);
        boolean pendingSync = destinations.stream()
                .anyMatch(result -> result.status() == DestinationStatus.PENDING_SYNC);
        if (pendingSync) {
            return BackupStatus.PARTIAL_SUCCESS;
        }
        if (succeeded && failed) {
            return BackupStatus.PARTIAL_SUCCESS;
        }
        if (succeeded) {
            return BackupStatus.SUCCESS;
        }
        if (failed) {
            return BackupStatus.FAILED;
        }
        return BackupStatus.SKIPPED;
    }
}
