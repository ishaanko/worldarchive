package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Per-world enablement, stable source path, and optional isolated Git remote. */
public record WorldConfig(
        WorldId worldId,
        boolean enabled,
        Path path,
        Optional<String> remoteUrl) {
    public WorldConfig {
        Objects.requireNonNull(worldId, "worldId");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl")
                .map(RemoteUrlPolicy::validatePlain);
    }

    public WorldConfig(WorldId worldId, boolean enabled, Path path) {
        this(worldId, enabled, path, Optional.empty());
    }
}
