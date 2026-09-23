package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import java.util.Objects;

/** Per-world storage measurement presented by the native client screen. */
public record StorageOverview(
        StoragePolicy policy,
        long gitBytes,
        long zipBytes,
        boolean unmeteredStoragePresent,
        StorageForecast forecast,
        boolean cleanupReviewRecommended) {
    public StorageOverview {
        Objects.requireNonNull(policy, "policy");
        if (gitBytes < 0 || zipBytes < 0) {
            throw new IllegalArgumentException("Storage usage must not be negative");
        }
        Objects.requireNonNull(forecast, "forecast");
    }

    public long totalBytes() {
        return Math.addExact(gitBytes, zipBytes);
    }
}
