package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.Objects;

/** Per-world storage measurement presented by the native client screen. */
public record StorageOverview(
        WorldId worldId,
        String worldName,
        StoragePolicy policy,
        long gitBytes,
        long zipBytes,
        boolean unmeteredStoragePresent,
        StorageForecast forecast,
        Instant measuredAt,
        boolean cleanupReviewRecommended) {
    public StorageOverview {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(policy, "policy");
        if (gitBytes < 0 || zipBytes < 0) {
            throw new IllegalArgumentException("Storage usage must not be negative");
        }
        Objects.requireNonNull(forecast, "forecast");
        Objects.requireNonNull(measuredAt, "measuredAt");
    }

    public long totalBytes() {
        return Math.addExact(gitBytes, zipBytes);
    }
}
