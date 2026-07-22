package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable, versioned user configuration with safe product defaults. */
public record WorldArchiveConfig(
        int schemaVersion,
        TriggerConfig triggers,
        GitDestinationConfig git,
        ZipDestinationConfig zip,
        List<WorldConfig> worlds) {
    public static final int CURRENT_SCHEMA_VERSION = 6;

    public WorldArchiveConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported configuration schema: " + schemaVersion);
        }
        Objects.requireNonNull(triggers, "triggers");
        Objects.requireNonNull(git, "git");
        if (git.remoteUrl().isPresent()) {
            throw new IllegalArgumentException(
                    "Global Git remote templates are not supported; configure each world instead");
        }
        Objects.requireNonNull(zip, "zip");
        worlds = List.copyOf(worlds);
        Set<WorldId> worldIds = new HashSet<>();
        Set<Path> worldPaths = new HashSet<>();
        for (WorldConfig world : worlds) {
            Objects.requireNonNull(world, "world");
            if (!worldIds.add(world.worldId())) {
                throw new IllegalArgumentException("Duplicate per-world configuration: " + world.worldId());
            }
            if (!worldPaths.add(world.path())) {
                throw new IllegalArgumentException("Multiple world IDs use the same path: " + world.path());
            }
        }
    }

    /** Compatibility constructor for configurations without per-world overrides. */
    public WorldArchiveConfig(
            int schemaVersion,
            TriggerConfig triggers,
            GitDestinationConfig git,
            ZipDestinationConfig zip) {
        this(schemaVersion, triggers, git, zip, List.of());
    }

    public static WorldArchiveConfig defaults() {
        return new WorldArchiveConfig(
                CURRENT_SCHEMA_VERSION,
                TriggerConfig.defaults(),
                GitDestinationConfig.defaults(),
                ZipDestinationConfig.defaults(),
                List.of());
    }

    /** Canonicalizes worlds and destinations and rejects destinations nested inside any known world. */
    public WorldArchiveConfig validateDestinations(Collection<Path> knownWorldPaths) throws IOException {
        Objects.requireNonNull(knownWorldPaths, "knownWorldPaths");
        List<WorldConfig> canonicalWorlds = new ArrayList<>(worlds.size());
        List<Path> allWorldPaths = new ArrayList<>(knownWorldPaths.size() + worlds.size());
        for (Path knownWorld : knownWorldPaths) {
            allWorldPaths.add(PathSafety.canonicalize(Objects.requireNonNull(knownWorld, "knownWorld")));
        }
        List<Path> canonicalWorldPaths = new ArrayList<>(worlds.size());
        for (WorldConfig world : worlds) {
            Path canonicalPath = PathSafety.canonicalize(world.path());
            canonicalWorldPaths.add(canonicalPath);
            allWorldPaths.add(canonicalPath);
        }
        for (int index = 0; index < worlds.size(); index++) {
            WorldConfig world = worlds.get(index);
            canonicalWorlds.add(new WorldConfig(
                    world.worldId(),
                    world.enabled(),
                    canonicalWorldPaths.get(index),
                    world.remoteUrl(),
                    canonicalizeDestination(world.zipDestination(), allWorldPaths)));
        }
        Optional<Path> gitRepository = canonicalizeDestination(git.repository(), allWorldPaths);
        Optional<Path> legacyGitRepository = canonicalizeDestination(
                git.legacyRepository(),
                allWorldPaths);
        Optional<Path> zipDestination = canonicalizeDestination(zip.destination(), allWorldPaths);
        return new WorldArchiveConfig(
                CURRENT_SCHEMA_VERSION,
                triggers,
                new GitDestinationConfig(
                        git.enabled(),
                        gitRepository,
                        git.remoteName(),
                        git.remoteUrl(),
                        git.triggers(),
                        git.lfsPatterns(),
                        git.health(),
                        legacyGitRepository,
                        git.legacyRemoteUrl()),
                new ZipDestinationConfig(zip.enabled(), zipDestination, zip.triggers(), zip.health()),
                canonicalWorlds);
    }

    private static Optional<Path> canonicalizeDestination(
            Optional<Path> destination,
            Collection<Path> sourceWorlds) throws IOException {
        if (destination.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PathSafety.requireOutsideWorlds(destination.get(), sourceWorlds));
    }
}
