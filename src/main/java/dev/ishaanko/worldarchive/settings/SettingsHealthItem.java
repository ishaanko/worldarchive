package dev.ishaanko.worldarchive.settings;

import java.util.Objects;

/** The state of one tool or folder in the settings footer, with a short message for its tooltip. */
public record SettingsHealthItem(SettingsHealthStatus status, String message) {
    public SettingsHealthItem {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    public static SettingsHealthItem unchecked() {
        return new SettingsHealthItem(SettingsHealthStatus.UNCHECKED, "not checked");
    }

    public static SettingsHealthItem disabled() {
        return new SettingsHealthItem(SettingsHealthStatus.DISABLED, "disabled");
    }

    public static SettingsHealthItem unconfigured() {
        return new SettingsHealthItem(SettingsHealthStatus.UNCONFIGURED, "not configured");
    }
}
