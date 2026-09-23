package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** The ZIP stores of one runtime state: each world's own ZIP folder, or the shared one. */
final class RuntimeZipBackupStores implements ZipBackupStoreResolver {
    private final RuntimeStoragePaths paths;

    /** The origin of each ZIP folder, decided once, because the stores are asked for often. */
    private final Map<Path, FolderOrigin> origins;

    RuntimeZipBackupStores(RuntimeStoragePaths paths, Function<Path, FolderOrigin> origin) {
        this.paths = Objects.requireNonNull(paths, "paths");
        Map<Path, FolderOrigin> decided = new HashMap<>();
        decided.put(paths.zipDirectory(), origin.apply(paths.zipDirectory()));
        paths.worldZipDirectories().values().forEach(folder -> decided.computeIfAbsent(folder, origin));
        this.origins = Map.copyOf(decided);
    }

    @Override
    public ZipBackupStore store(WorldId worldId) {
        Path root = paths.zipDirectory(worldId);
        return new ZipBackupStore(root, origins.get(root));
    }

    @Override
    public ZipBackupStore defaultStore() {
        return new ZipBackupStore(paths.zipDirectory(), origins.get(paths.zipDirectory()));
    }
}
