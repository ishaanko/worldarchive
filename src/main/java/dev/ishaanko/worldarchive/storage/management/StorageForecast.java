package dev.ishaanko.worldarchive.storage.management;

import java.util.OptionalLong;

/** Honest forecast derived from observed managed-local growth. */
public record StorageForecast(State state, OptionalLong daysRemaining) {
    public StorageForecast {
        if (daysRemaining.isPresent() && daysRemaining.getAsLong() < 0) {
            throw new IllegalArgumentException("Forecast days must not be negative");
        }
        if ((state == State.ESTIMATED) != daysRemaining.isPresent()) {
            throw new IllegalArgumentException("Only estimated forecasts carry remaining days");
        }
    }

    public enum State {
        DISABLED,
        LEARNING,
        STABLE,
        ESTIMATED,
        REACHED
    }
}
