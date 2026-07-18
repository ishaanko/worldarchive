package dev.ishaankot.worldarchive.settings;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Credential-free input for asynchronous settings health probing. */
public record SettingsProbeRequest(
        boolean gitEnabled,
        String gitExecutable,
        Optional<Path> gitRepository,
        boolean remoteConfigured,
        boolean zipEnabled,
        Optional<Path> zipDirectory) {
    public SettingsProbeRequest {
        gitExecutable = Objects.requireNonNull(gitExecutable, "gitExecutable");
        if (gitExecutable.isBlank() || gitExecutable.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Git executable is invalid");
        }
        gitRepository = normalize(gitRepository, "gitRepository");
        zipDirectory = normalize(zipDirectory, "zipDirectory");
    }

    private static Optional<Path> normalize(Optional<Path> path, String name) {
        return Objects.requireNonNull(path, name)
                .map(value -> value.toAbsolutePath().normalize());
    }
}
