package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;

/** Credential-safe status for one tool or destination component. */
public record SettingsHealthItem(SettingsHealthStatus status, String message) {
    public SettingsHealthItem {
        Objects.requireNonNull(status, "status");
        message = SensitiveDataRedactor.redact(Objects.requireNonNull(message, "message")).strip();
        if (message.isEmpty()
                || message.length() > 512
                || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Settings health message is invalid");
        }
    }

    public static SettingsHealthItem unchecked() {
        return new SettingsHealthItem(SettingsHealthStatus.UNCHECKED, "not checked");
    }

    public static SettingsHealthItem disabled() {
        return new SettingsHealthItem(SettingsHealthStatus.DISABLED, "disabled");
    }
}
