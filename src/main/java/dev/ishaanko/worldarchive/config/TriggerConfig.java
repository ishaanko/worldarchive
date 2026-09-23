package dev.ishaanko.worldarchive.config;

import dev.ishaanko.worldarchive.model.BackupTrigger;
import java.util.Objects;

/**
 * Which triggers may start a backup at all, and the minutes between scheduled backups. Each
 * destination narrows the triggers further with its {@link DestinationTriggerConfig}.
 */
public record TriggerConfig(
        boolean manualEnabled,
        boolean worldExitEnabled,
        boolean scheduledEnabled,
        int scheduleIntervalMinutes) {
    public static final int DEFAULT_SCHEDULE_INTERVAL_MINUTES = 30;

    public static final int MAXIMUM_SCHEDULE_INTERVAL_MINUTES = 10_080;

    public TriggerConfig {
        if (!isValidInterval(scheduleIntervalMinutes)) {
            throw new IllegalArgumentException("Schedule interval must be between 1 minute and 7 days");
        }
    }

    public static TriggerConfig defaults() {
        return new TriggerConfig(true, true, false, DEFAULT_SCHEDULE_INTERVAL_MINUTES);
    }

    public boolean enabledFor(BackupTrigger trigger) {
        return switch (Objects.requireNonNull(trigger, "trigger")) {
            case MANUAL -> manualEnabled;
            case WORLD_EXIT -> worldExitEnabled;
            case SCHEDULED -> scheduledEnabled;
        };
    }

    /** True for a whole number of minutes from 1 to {@link #MAXIMUM_SCHEDULE_INTERVAL_MINUTES}. */
    public static boolean isValidInterval(int minutes) {
        return minutes >= 1 && minutes <= MAXIMUM_SCHEDULE_INTERVAL_MINUTES;
    }
}
