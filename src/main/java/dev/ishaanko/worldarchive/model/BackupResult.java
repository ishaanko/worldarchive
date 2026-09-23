package dev.ishaanko.worldarchive.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of one backup across its destinations. The overall {@link #status()} is derived
 * from the destinations and never stored on its own, so a change to the rules cannot make an
 * old catalog record invalid.
 */
public record BackupResult(
        BackupId backupId,
        WorldId worldId,
        List<DestinationResult> destinations,
        Instant completedAt) {
    public BackupResult {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(worldId, "worldId");
        destinations = List.copyOf(destinations);
        if (destinations.stream().map(DestinationResult::destination).distinct().count() != destinations.size()) {
            throw new IllegalArgumentException("Each destination may appear at most once");
        }
        Objects.requireNonNull(completedAt, "completedAt");
    }

    /**
     * SUCCESS when a copy exists and nothing failed or waits; PARTIAL_SUCCESS when a copy exists
     * but another destination failed or a remote still waits for its push; FAILED when only
     * failures remain; SKIPPED when no destination ran.
     */
    public BackupStatus status() {
        boolean succeeded = destinations.stream()
                .anyMatch(result -> result.status() == DestinationStatus.SUCCESS);
        boolean failed = destinations.stream()
                .anyMatch(result -> result.status() == DestinationStatus.FAILED);
        boolean pendingSync = destinations.stream()
                .anyMatch(result -> result.status() == DestinationStatus.PENDING_SYNC);
        if (pendingSync || succeeded && failed) {
            return BackupStatus.PARTIAL_SUCCESS;
        }
        if (succeeded) {
            return BackupStatus.SUCCESS;
        }
        return failed ? BackupStatus.FAILED : BackupStatus.SKIPPED;
    }
}
