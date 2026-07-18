package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.config.TriggerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe interval clock that emits at most one run after any period of inactivity. */
public final class NoCatchUpSchedule {
    private final Duration interval;

    private Instant nextRunAt;

    public NoCatchUpSchedule(Duration interval, Instant startedAt) {
        this.interval = requireInterval(interval);
        reset(startedAt);
    }

    public static NoCatchUpSchedule minutes(int intervalMinutes, Instant startedAt) {
        if (intervalMinutes < 1
                || intervalMinutes > TriggerConfig.MAXIMUM_SCHEDULE_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("Schedule interval must be between one minute and seven days");
        }
        return new NoCatchUpSchedule(Duration.ofMinutes(intervalMinutes), startedAt);
    }

    /** Returns the due instant once, then schedules from now instead of replaying missed intervals. */
    public synchronized Optional<Instant> poll(Instant now) {
        Objects.requireNonNull(now, "now");
        if (now.isBefore(nextRunAt)) {
            return Optional.empty();
        }
        Instant due = nextRunAt;
        nextRunAt = now.plus(interval);
        return Optional.of(due);
    }

    /** Starts a fresh interval, as on world open or application restart. */
    public synchronized void reset(Instant now) {
        Objects.requireNonNull(now, "now");
        nextRunAt = now.plus(interval);
    }

    public synchronized Instant nextRunAt() {
        return nextRunAt;
    }

    public Duration interval() {
        return interval;
    }

    private static Duration requireInterval(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Schedule interval must be positive");
        }
        if (interval.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Schedule interval must not exceed seven days");
        }
        return interval;
    }
}
