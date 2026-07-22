package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Per-world enablement, source path, and optional destination overrides. */
public record WorldConfig(
        WorldId worldId,
        boolean enabled,
        Path path,
        Optional<String> remoteUrl,
        Optional<Path> zipDestination) {
    public WorldConfig {
        Objects.requireNonNull(worldId, "worldId");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl")
                .map(RemoteUrlPolicy::validatePlain);
        zipDestination = Objects.requireNonNull(zipDestination, "zipDestination")
                .map(destination -> destination.toAbsolutePath().normalize());
    }

    public WorldConfig(
            WorldId worldId,
            boolean enabled,
            Path path,
            Optional<String> remoteUrl) {
        this(worldId, enabled, path, remoteUrl, Optional.empty());
    }

    public WorldConfig(WorldId worldId, boolean enabled, Path path) {
        this(worldId, enabled, path, Optional.empty(), Optional.empty());
    }
}
