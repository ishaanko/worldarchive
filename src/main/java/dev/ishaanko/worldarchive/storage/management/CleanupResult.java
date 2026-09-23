package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.model.BackupId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a cleanup did: the storage used before and after, why each backup that kept its copies
 * kept them, and a warning about work that failed after every delete, such as freeing Git space.
 */
public record CleanupResult(
        long bytesBefore,
        long bytesAfter,
        Map<BackupId, String> failures,
        Optional<String> warning) {
    public CleanupResult {
        if (bytesBefore < 0 || bytesAfter < 0) {
            throw new IllegalArgumentException("Cleanup result sizes must not be negative");
        }
        failures = Map.copyOf(Objects.requireNonNull(failures, "failures"));
        Objects.requireNonNull(warning, "warning");
    }

    public long reclaimedBytes() {
        return Math.max(0, bytesBefore - bytesAfter);
    }
}
