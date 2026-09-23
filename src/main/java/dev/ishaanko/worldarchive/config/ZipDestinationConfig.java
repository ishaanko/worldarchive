package dev.ishaanko.worldarchive.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Settings of the ZIP destination. An empty destination means the default folder
 * ({@link DefaultDestinations}); a world can name its own folder in {@link WorldConfig}.
 */
public record ZipDestinationConfig(
        boolean enabled,
        Optional<Path> destination,
        DestinationTriggerConfig triggers) {
    public ZipDestinationConfig {
        destination = Objects.requireNonNull(destination, "destination")
                .map(path -> path.toAbsolutePath().normalize());
        Objects.requireNonNull(triggers, "triggers");
    }

    public static ZipDestinationConfig defaults() {
        return new ZipDestinationConfig(true, Optional.empty(), DestinationTriggerConfig.defaults());
    }

    public ZipDestinationConfig withDestination(Optional<Path> destination) {
        return new ZipDestinationConfig(enabled, destination, triggers);
    }
}
