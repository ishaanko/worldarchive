package dev.ishaanko.worldarchive.storage.management;

import java.time.Instant;
import java.util.Objects;

/** One bounded observation of managed-local bytes for a world. */
public record StorageSample(Instant measuredAt, long bytes) {
    public StorageSample {
        Objects.requireNonNull(measuredAt, "measuredAt");
        if (bytes < 0) {
            throw new IllegalArgumentException("Storage sample bytes must not be negative");
        }
    }
}
