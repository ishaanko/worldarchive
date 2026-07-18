package dev.ishaankot.worldarchive.ui.model;

import java.util.Objects;

/** Enablement and stable disabled reason for one action. */
public record BackupActionAvailability(boolean enabled, ActionDisabledReason reason) {
    public BackupActionAvailability {
        Objects.requireNonNull(reason, "reason");
        if (enabled != (reason == ActionDisabledReason.NONE)) {
            throw new IllegalArgumentException("Enabled actions must have NONE as their reason");
        }
    }

    public static BackupActionAvailability available() {
        return new BackupActionAvailability(true, ActionDisabledReason.NONE);
    }

    public static BackupActionAvailability disabled(ActionDisabledReason reason) {
        if (reason == ActionDisabledReason.NONE) {
            throw new IllegalArgumentException("A disabled action requires a reason");
        }
        return new BackupActionAvailability(false, reason);
    }
}
