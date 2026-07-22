package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Immutable destination roots used by one runtime state. */
record RuntimeStoragePaths(
        Path gitRepository,
        Path zipDirectory,
        Map<WorldId, Path> worldZipDirectories) {
    RuntimeStoragePaths {
        gitRepository = normalize(gitRepository, "gitRepository");
        zipDirectory = normalize(zipDirectory, "zipDirectory");
        worldZipDirectories = Map.copyOf(Objects.requireNonNull(
                worldZipDirectories, "worldZipDirectories"));
    }

    RuntimeStoragePaths(Path gitRepository, Path zipDirectory) {
        this(gitRepository, zipDirectory, Map.of());
    }

    static RuntimeStoragePaths from(WorldArchiveConfig config, Path storageRoot) {
        Objects.requireNonNull(config, "config");
        Path root = normalize(storageRoot, "storageRoot");
        return new RuntimeStoragePaths(
                config.git().repository().orElseGet(() -> root.resolve("worldarchive.git")),
                config.zip().destination().orElseGet(() -> root.resolve("archives")),
                config.worlds().stream()
                        .filter(world -> world.zipDestination().isPresent())
                        .collect(Collectors.toUnmodifiableMap(
                                world -> world.worldId(),
                                world -> normalize(
                                        world.zipDestination().orElseThrow(),
                                        "worldZipDestination"))));
    }

    Path zipDirectory(WorldId worldId) {
        return worldZipDirectories.getOrDefault(
                Objects.requireNonNull(worldId, "worldId"), zipDirectory);
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
