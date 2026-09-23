package dev.ishaanko.worldarchive.config;

import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** One world's settings: whether it is backed up, where its folder is, and its own remote and ZIP folder. */
public record WorldConfig(
        WorldId worldId,
        boolean enabled,
        Path path,
        Optional<String> remoteUrl,
        Optional<Path> zipDestination,
        StoragePolicy storagePolicy) {
    public WorldConfig {
        Objects.requireNonNull(worldId, "worldId");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl")
                .map(RemoteUrlPolicy::validateConfiguredPlain);
        zipDestination = Objects.requireNonNull(zipDestination, "zipDestination")
                .map(destination -> destination.toAbsolutePath().normalize());
        Objects.requireNonNull(storagePolicy, "storagePolicy");
    }

    /** Settings for a world seen for the first time: backed up, with no remote or own ZIP folder. */
    public static WorldConfig defaults(WorldId worldId, Path path) {
        return new WorldConfig(worldId, true, path, Optional.empty(), Optional.empty(), StoragePolicy.defaults());
    }

    public WorldConfig withEnabled(boolean enabled) {
        return new WorldConfig(worldId, enabled, path, remoteUrl, zipDestination, storagePolicy);
    }

    public WorldConfig withPath(Path path) {
        return new WorldConfig(worldId, enabled, path, remoteUrl, zipDestination, storagePolicy);
    }

    public WorldConfig withRemoteUrl(Optional<String> remoteUrl) {
        return new WorldConfig(worldId, enabled, path, remoteUrl, zipDestination, storagePolicy);
    }

    public WorldConfig withZipDestination(Optional<Path> zipDestination) {
        return new WorldConfig(worldId, enabled, path, remoteUrl, zipDestination, storagePolicy);
    }

    public WorldConfig withStoragePolicy(StoragePolicy storagePolicy) {
        return new WorldConfig(worldId, enabled, path, remoteUrl, zipDestination, storagePolicy);
    }
}
