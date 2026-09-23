package dev.ishaanko.worldarchive.config;

import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Immutable user configuration with safe product defaults. Change one part with the
 * {@code with...} methods; they keep every other field.
 */
public record WorldArchiveConfig(
        TriggerConfig triggers,
        GitDestinationConfig git,
        ZipDestinationConfig zip,
        List<WorldConfig> worlds) {
    /** The schema version that {@link WorldArchiveConfigStore} writes. */
    public static final int CURRENT_SCHEMA_VERSION = 7;

    public WorldArchiveConfig {
        Objects.requireNonNull(triggers, "triggers");
        Objects.requireNonNull(git, "git");
        Objects.requireNonNull(zip, "zip");
        worlds = List.copyOf(worlds);
        Set<WorldId> worldIds = new HashSet<>();
        Set<Path> worldPaths = new HashSet<>();
        for (WorldConfig world : worlds) {
            if (!worldIds.add(world.worldId())) {
                throw new IllegalArgumentException("Duplicate per-world configuration: " + world.worldId());
            }
            if (!worldPaths.add(world.path())) {
                throw new IllegalArgumentException("Multiple world IDs use the same path: " + world.path());
            }
        }
    }

    public static WorldArchiveConfig defaults() {
        return new WorldArchiveConfig(
                TriggerConfig.defaults(),
                GitDestinationConfig.defaults(),
                ZipDestinationConfig.defaults(),
                List.of());
    }

    public WorldArchiveConfig withGit(GitDestinationConfig git) {
        return new WorldArchiveConfig(triggers, git, zip, worlds);
    }

    public WorldArchiveConfig withZip(ZipDestinationConfig zip) {
        return new WorldArchiveConfig(triggers, git, zip, worlds);
    }

    public WorldArchiveConfig withWorlds(List<WorldConfig> worlds) {
        return new WorldArchiveConfig(triggers, git, zip, worlds);
    }

    /**
     * Replaces the settings of one configured world and keeps the order of the list.
     *
     * @throws IllegalArgumentException when no world with this ID is configured
     */
    public WorldArchiveConfig withWorld(WorldId worldId, UnaryOperator<WorldConfig> update) {
        Objects.requireNonNull(update, "update");
        if (world(worldId).isEmpty()) {
            throw new IllegalArgumentException("No settings exist for world " + worldId);
        }
        return withWorlds(worlds.stream()
                .map(world -> world.worldId().equals(worldId) ? update.apply(world) : world)
                .toList());
    }

    /** The settings of one world, when it is configured. */
    public Optional<WorldConfig> world(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return worlds.stream().filter(world -> world.worldId().equals(worldId)).findFirst();
    }

    /**
     * The same configuration with every world and destination path in canonical form (see
     * {@link PathSafety#canonicalize}).
     */
    public WorldArchiveConfig canonicalize() throws IOException {
        List<WorldConfig> canonicalWorlds = new ArrayList<>(worlds.size());
        for (WorldConfig world : worlds) {
            canonicalWorlds.add(world
                    .withPath(PathSafety.canonicalize(world.path()))
                    .withZipDestination(canonical(world.zipDestination())));
        }
        return new WorldArchiveConfig(
                triggers,
                git.withRepository(canonical(git.repository())),
                zip.withDestination(canonical(zip.destination())),
                canonicalWorlds);
    }

    /**
     * Canonicalizes (see {@link #canonicalize}) and refuses any destination inside a world: a
     * configured world or one of the given world folders. Destinations that are switched off
     * are checked too, so switching one on later can never start writing into a world.
     *
     * @throws IOException when a destination is inside a world
     */
    public WorldArchiveConfig validateDestinations(Collection<Path> knownWorldPaths) throws IOException {
        WorldArchiveConfig canonical = canonicalize();
        Set<Path> sourceWorlds = new LinkedHashSet<>(PathSafety.canonicalizeAll(knownWorldPaths));
        canonical.worlds().forEach(world -> sourceWorlds.add(world.path()));
        for (Path destination : canonical.destinations()) {
            PathSafety.requireOutsideWorlds(destination, sourceWorlds);
        }
        return canonical;
    }

    /** Every destination folder the settings name: the Git folder, the ZIP folder, and each world's own ZIP folder. */
    public List<Path> destinations() {
        List<Path> destinations = new ArrayList<>(worlds.size() + 2);
        git.repository().ifPresent(destinations::add);
        zip.destination().ifPresent(destinations::add);
        worlds.forEach(world -> world.zipDestination().ifPresent(destinations::add));
        return destinations;
    }

    private static Optional<Path> canonical(Optional<Path> path) throws IOException {
        return path.isEmpty() ? path : Optional.of(PathSafety.canonicalize(path.get()));
    }
}
