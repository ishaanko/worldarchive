package dev.ishaankot.worldarchive.config;

/** Immutable backup trigger settings. */
public record TriggerConfig(
        boolean manualEnabled,
        boolean worldExitEnabled,
        boolean scheduledEnabled,
        int scheduleIntervalMinutes) {
    public static final int DEFAULT_SCHEDULE_INTERVAL_MINUTES = 30;

    public static final int MAXIMUM_SCHEDULE_INTERVAL_MINUTES = 10_080;

    public TriggerConfig {
        if (scheduleIntervalMinutes < 1
                || scheduleIntervalMinutes > MAXIMUM_SCHEDULE_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("Schedule interval must be between 1 minute and 7 days");
        }
    }

    public static TriggerConfig defaults() {
        return new TriggerConfig(true, true, false, DEFAULT_SCHEDULE_INTERVAL_MINUTES);
    }
}
