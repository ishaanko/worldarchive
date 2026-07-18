package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldArchiveConfigStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared settings access used by every UI and command entry point. */
public final class WorldArchiveSettingsRepository {
    private final WorldArchiveConfigStore store;

    private final Supplier<? extends Collection<Path>> knownWorldPaths;

    private final SettingsDefaults defaults;

    private WorldArchiveConfig current;

    public WorldArchiveSettingsRepository(
            WorldArchiveConfigStore store,
            Supplier<? extends Collection<Path>> knownWorldPaths) {
        this(store, knownWorldPaths, SettingsDefaults.fromConfigFile(
                Objects.requireNonNull(store, "store").file()));
    }

    public WorldArchiveSettingsRepository(
            WorldArchiveConfigStore store,
            Supplier<? extends Collection<Path>> knownWorldPaths,
            SettingsDefaults defaults) {
        this.store = Objects.requireNonNull(store, "store");
        this.knownWorldPaths = Objects.requireNonNull(knownWorldPaths, "knownWorldPaths");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        current = defaults.resolve(WorldArchiveConfig.defaults());
    }

    public synchronized WorldArchiveConfig load() throws IOException {
        List<Path> worlds = snapshotWorldPaths();
        WorldArchiveConfig loaded = store.load(worlds);
        WorldArchiveConfig resolved = defaults.resolve(loaded).validateDestinations(worlds);
        if (!resolved.equals(loaded)) {
            store.save(resolved, worlds);
        }
        current = resolved;
        return current;
    }

    public synchronized void save(WorldArchiveConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        List<Path> worlds = snapshotWorldPaths();
        WorldArchiveConfig validated = defaults.resolve(config).validateDestinations(worlds);
        store.save(validated, worlds);
        current = validated;
    }

    public synchronized WorldArchiveConfig current() {
        return current;
    }

    public List<Path> currentKnownWorldPaths() {
        return snapshotWorldPaths();
    }

    public Path file() {
        return store.file();
    }

    private List<Path> snapshotWorldPaths() {
        Collection<Path> supplied = Objects.requireNonNull(
                knownWorldPaths.get(),
                "knownWorldPaths result");
        return supplied.stream().map(path -> Objects.requireNonNull(path, "knownWorldPath")).toList();
    }
}
