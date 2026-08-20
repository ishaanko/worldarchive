package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.DestinationType;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Combines the progress of the destinations of one create operation into a single monotonic stream. */
final class DestinationProgressAggregator {
    /** Message reported while the destinations write; per-backend messages stay internal. */
    static final String WRITING_MESSAGE = "Writing backup destinations";

    /** Scale used when the capture reports no source bytes, so a fraction stays reportable. */
    private static final long FALLBACK_TOTAL_UNITS = 1_000L;

    private final Set<DestinationType> destinations;

    private final long totalUnits;

    /** Newest fraction of each destination that has reported; a missing destination counts as zero. */
    private final Map<DestinationType, Double> fractions = new ConcurrentHashMap<>();

    /** High-water mark that keeps the reported value monotonically non-decreasing. */
    private final AtomicLong reportedUnits = new AtomicLong();

    DestinationProgressAggregator(Collection<DestinationType> destinations, long sourceByteCount) {
        this.destinations = Set.copyOf(Objects.requireNonNull(destinations, "destinations"));
        if (this.destinations.isEmpty()) {
            throw new IllegalArgumentException("An operation must write at least one destination");
        }
        this.totalUnits = sourceByteCount > 0 ? sourceByteCount : FALLBACK_TOTAL_UNITS;
    }

    /** True when more than one destination writes, and the progress of each one must be combined. */
    boolean aggregates() {
        return destinations.size() > 1;
    }

    long totalUnits() {
        return totalUnits;
    }

    /**
     * Records the newest progress of one destination and returns the combined completed units.
     *
     * <p>The returned value never decreases, because a destination that goes back to an earlier
     * phase must not move the bar of the whole operation backward.</p>
     */
    long accept(DestinationType destination, OperationProgress progress) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(progress, "progress");
        if (!destinations.contains(destination)) {
            return reportedUnits.get();
        }
        fractions.put(destination, fractionOf(progress));
        double combined = 0;
        for (double fraction : fractions.values()) {
            combined += fraction;
        }
        long candidate = Math.round(combined / destinations.size() * totalUnits);
        return reportedUnits.accumulateAndGet(Math.clamp(candidate, 0, totalUnits), Math::max);
    }

    /** A destination that has finished counts as done; an unknown total counts as not started. */
    private static double fractionOf(OperationProgress progress) {
        if (progress.phase() == OperationPhase.COMPLETE || progress.phase() == OperationPhase.FAILED) {
            return 1;
        }
        return Math.clamp(progress.fraction().orElse(0), 0, 1);
    }
}
