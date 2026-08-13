package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Process-lifetime bidirectional guard against copied or conflicting world identities. */
final class RuntimeWorldPathRegistry {
    private final Map<WorldId, Path> pathsByWorld = new HashMap<>();

    private final Map<Path, WorldId> worldsByPath = new HashMap<>();

    synchronized void configure(WorldId worldId, Path worldDirectory) {
        Objects.requireNonNull(worldId, "worldId");
        Path world = normalize(worldDirectory);
        Path previous = pathsByWorld.put(worldId, world);
        if (previous != null && !previous.equals(world)) {
            worldsByPath.remove(previous, worldId);
        }
        worldsByPath.put(world, worldId);
    }

    synchronized boolean register(WorldId worldId, Path worldDirectory) {
        Objects.requireNonNull(worldId, "worldId");
        Path world = normalize(worldDirectory);
        Path registeredPath = pathsByWorld.get(worldId);
        WorldId registeredWorld = worldsByPath.get(world);
        if ((registeredPath != null && !registeredPath.equals(world))
                || (registeredWorld != null && !registeredWorld.equals(worldId))) {
            return false;
        }
        pathsByWorld.put(worldId, world);
        worldsByPath.put(world, worldId);
        return true;
    }

    synchronized boolean matches(WorldId worldId, Path worldDirectory) {
        Path world = normalize(worldDirectory);
        Path registeredPath = pathsByWorld.get(worldId);
        WorldId registeredWorld = worldsByPath.get(world);
        return (registeredPath == null || registeredPath.equals(world))
                && (registeredWorld == null || registeredWorld.equals(worldId));
    }

    synchronized boolean isRegistered(WorldId worldId, Path worldDirectory) {
        Path world = normalize(worldDirectory);
        return world.equals(pathsByWorld.get(worldId))
                && worldId.equals(worldsByPath.get(world));
    }

    synchronized Optional<Path> claimedPath(WorldId worldId) {
        return Optional.ofNullable(pathsByWorld.get(Objects.requireNonNull(worldId, "worldId")));
    }

    synchronized Optional<WorldId> claimedWorld(Path worldDirectory) {
        return Optional.ofNullable(worldsByPath.get(normalize(worldDirectory)));
    }

    /** Releases one claim after the caller verified it no longer matches the folder on disk. */
    synchronized void release(WorldId worldId, Path worldDirectory) {
        Objects.requireNonNull(worldId, "worldId");
        Path world = normalize(worldDirectory);
        if (world.equals(pathsByWorld.get(worldId))) {
            pathsByWorld.remove(worldId);
        }
        if (worldId.equals(worldsByPath.get(world))) {
            worldsByPath.remove(world);
        }
    }

    synchronized List<Path> snapshotPaths() {
        return List.copyOf(worldsByPath.keySet());
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "worldDirectory").toAbsolutePath().normalize();
    }
}
