package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** The backup folders of one runtime state: the Git root, the ZIP folder, and each world's own ZIP folder. */
public record RuntimeStoragePaths(
        Path gitRepository,
        Path zipDirectory,
        Map<WorldId, Path> worldZipDirectories) {
    public RuntimeStoragePaths {
        gitRepository = normalize(gitRepository, "gitRepository");
        zipDirectory = normalize(zipDirectory, "zipDirectory");
        worldZipDirectories = Map.copyOf(Objects.requireNonNull(worldZipDirectories, "worldZipDirectories"));
    }

    /**
     * The folders of settings whose default folders are filled in, as
     * {@link dev.ishaanko.worldarchive.config.DefaultDestinations#resolve} does.
     */
    static RuntimeStoragePaths from(WorldArchiveConfig config) {
        return new RuntimeStoragePaths(
                config.git().repository().orElseThrow(() -> new IllegalArgumentException("The Git folder is not set")),
                config.zip().destination().orElseThrow(() -> new IllegalArgumentException("The ZIP folder is not set")),
                config.worlds().stream()
                        .filter(world -> world.zipDestination().isPresent())
                        .collect(Collectors.toUnmodifiableMap(
                                WorldConfig::worldId,
                                world -> normalize(world.zipDestination().orElseThrow(), "worldZipDestination"))));
    }

    /** The ZIP folder of one world: its own folder when it has one, otherwise the shared one. */
    public Path zipDirectory(WorldId worldId) {
        return worldZipDirectories.getOrDefault(Objects.requireNonNull(worldId, "worldId"), zipDirectory);
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
