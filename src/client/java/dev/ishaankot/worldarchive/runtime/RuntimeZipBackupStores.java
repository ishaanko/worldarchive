package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.util.Objects;

/** Creates lightweight store handles against the immutable roots of one runtime state. */
final class RuntimeZipBackupStores implements ZipBackupStoreResolver {
    private final RuntimeStoragePaths paths;

    RuntimeZipBackupStores(RuntimeStoragePaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public ZipBackupStore store(WorldId worldId) {
        return new ZipBackupStore(paths.zipDirectory(worldId));
    }

    @Override
    public ZipBackupStore defaultStore() {
        return new ZipBackupStore(paths.zipDirectory());
    }
}
