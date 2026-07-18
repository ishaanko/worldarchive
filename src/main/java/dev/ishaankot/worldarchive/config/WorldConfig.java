package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;

/** Per-world enablement and stable source path. */
public record WorldConfig(WorldId worldId, boolean enabled, Path path) {
    public WorldConfig {
        Objects.requireNonNull(worldId, "worldId");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
