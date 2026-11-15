package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What one conversion of a world's Git backups to the bucket format did: the backups whose
 * snapshot now points at a converted commit, how many needed nothing (already in the new format,
 * or with no region file the bucket format can hold), the ones that could not be converted and
 * why, and how many bytes the repository shrank by. A cancelled conversion still lists what it
 * converted before it stopped.
 */
public record GitConversion(
        List<BackupId> converted,
        int unchanged,
        List<Failure> failures,
        long bytesFreed,
        boolean cancelled) {
    public GitConversion {
        converted = List.copyOf(converted);
        failures = List.copyOf(failures);
        if (unchanged < 0 || bytesFreed < 0) {
            throw new IllegalArgumentException("Conversion counts must not be negative");
        }
    }

    /** A backup that was left as it was, with the time it was made and the reason. */
    public record Failure(BackupId backupId, Instant createdAt, String reason) {
        public Failure {
            Objects.requireNonNull(backupId, "backupId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
