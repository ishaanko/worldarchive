package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;

/** A world folder found on disk and the identity it carries. */
public record DiscoveredWorld(Path path, WorldId worldId) {
    public DiscoveredWorld {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(worldId, "worldId");
    }
}
