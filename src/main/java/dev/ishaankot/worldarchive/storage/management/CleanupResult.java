package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Map;
import java.util.Objects;

/** Per-item cleanup outcomes plus the actual post-operation measurement. */
public record CleanupResult(
        WorldId worldId,
        long bytesBefore,
        long bytesAfter,
        Map<BackupId, String> failures) {
    public CleanupResult {
        Objects.requireNonNull(worldId, "worldId");
        if (bytesBefore < 0 || bytesAfter < 0) {
            throw new IllegalArgumentException("Cleanup result sizes must not be negative");
        }
        failures = Map.copyOf(Objects.requireNonNull(failures, "failures"));
    }

    public long reclaimedBytes() {
        return Math.max(0, bytesBefore - bytesAfter);
    }
}
