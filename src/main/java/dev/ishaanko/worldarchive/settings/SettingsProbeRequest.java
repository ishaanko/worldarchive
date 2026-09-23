package dev.ishaanko.worldarchive.settings;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** What the settings footer checks: the destinations that are switched on, and their folders. */
public record SettingsProbeRequest(
        boolean gitEnabled,
        Optional<Path> gitRepository,
        boolean zipEnabled,
        Optional<Path> zipFolder) {
    public SettingsProbeRequest {
        gitRepository = normalize(gitRepository, "gitRepository");
        zipFolder = normalize(zipFolder, "zipFolder");
    }

    private static Optional<Path> normalize(Optional<Path> path, String name) {
        return Objects.requireNonNull(path, name)
                .map(value -> value.toAbsolutePath().normalize());
    }
}
