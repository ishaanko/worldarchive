package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.BackupTrigger;
import java.util.Objects;

/** Per-destination trigger gates applied after the global trigger settings. */
public record DestinationTriggerConfig(
        boolean manualEnabled,
        boolean worldExitEnabled,
        boolean scheduledEnabled) {
    public static DestinationTriggerConfig defaults() {
        return new DestinationTriggerConfig(true, true, true);
    }

    public boolean enabledFor(BackupTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        return switch (trigger) {
            case MANUAL -> manualEnabled;
            case WORLD_EXIT -> worldExitEnabled;
            case SCHEDULED -> scheduledEnabled;
        };
    }
}
