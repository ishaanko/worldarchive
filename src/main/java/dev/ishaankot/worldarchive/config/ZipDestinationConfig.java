package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Configuration for local or desktop-synced ZIP archives. */
public record ZipDestinationConfig(
        boolean enabled,
        Optional<Path> destination,
        DestinationTriggerConfig triggers,
        DestinationHealth health) {
    public ZipDestinationConfig {
        destination = Objects.requireNonNull(destination, "destination")
                .map(path -> path.toAbsolutePath().normalize());
        Objects.requireNonNull(triggers, "triggers");
        Objects.requireNonNull(health, "health");
        if (health.destination() != DestinationType.ZIP) {
            throw new IllegalArgumentException("ZIP health state must describe the ZIP destination");
        }
    }

    /** Compatibility constructor for callers that predate persisted destination health. */
    public ZipDestinationConfig(
            boolean enabled,
            Optional<Path> destination,
            DestinationTriggerConfig triggers) {
        this(enabled, destination, triggers, DestinationHealth.notChecked(DestinationType.ZIP));
    }

    /** Compatibility constructor for callers that predate per-destination triggers. */
    public ZipDestinationConfig(boolean enabled, Optional<Path> destination) {
        this(enabled, destination, DestinationTriggerConfig.defaults());
    }

    public static ZipDestinationConfig defaults() {
        return new ZipDestinationConfig(
                true,
                Optional.empty(),
                DestinationTriggerConfig.defaults(),
                DestinationHealth.notChecked(DestinationType.ZIP));
    }
}
