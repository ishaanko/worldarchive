package dev.ishaankot.worldarchive.runtime;

import java.util.Objects;

/** Binds an externally prepared capture to its originating state and configuration permit. */
record PreparedOwnership(
        RuntimeState state,
        RuntimeConfigurationGate.Permit permit) {
    PreparedOwnership {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(permit, "permit");
    }
}
