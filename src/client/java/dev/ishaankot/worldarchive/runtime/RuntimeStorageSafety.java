package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed runtime state when a destination overlaps any known world. */
final class RuntimeStorageSafety {
    static final String WARNING =
            "A backup destination overlaps a known world; storage actions are disabled until settings are fixed";

    private final AtomicReference<Optional<String>> warning =
            new AtomicReference<>(Optional.empty());

    void refresh(WorldArchiveConfig config, List<Path> worldPaths) {
        warning.set(issue(config, worldPaths));
    }

    Optional<String> warning() {
        return warning.get();
    }

    static Optional<String> issue(WorldArchiveConfig config, List<Path> worldPaths) {
        try {
            Objects.requireNonNull(config, "config").validateDestinations(
                    List.copyOf(Objects.requireNonNull(worldPaths, "worldPaths")));
            return Optional.empty();
        } catch (IOException exception) {
            return Optional.of(WARNING);
        }
    }
}
