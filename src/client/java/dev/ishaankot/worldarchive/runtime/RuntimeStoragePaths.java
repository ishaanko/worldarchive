package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable destination roots used by one runtime state. */
record RuntimeStoragePaths(Path gitRepository, Path zipDirectory) {
    RuntimeStoragePaths {
        gitRepository = normalize(gitRepository, "gitRepository");
        zipDirectory = normalize(zipDirectory, "zipDirectory");
    }

    static RuntimeStoragePaths from(WorldArchiveConfig config, Path storageRoot) {
        Objects.requireNonNull(config, "config");
        Path root = normalize(storageRoot, "storageRoot");
        return new RuntimeStoragePaths(
                config.git().repository().orElseGet(() -> root.resolve("worldarchive.git")),
                config.zip().destination().orElseGet(() -> root.resolve("archives")));
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
