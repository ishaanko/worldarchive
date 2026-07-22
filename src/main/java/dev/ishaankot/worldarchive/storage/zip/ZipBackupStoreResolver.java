package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.WorldId;

/** Resolves the managed ZIP root that owns one world's archives. */
@FunctionalInterface
public interface ZipBackupStoreResolver {
    ZipBackupStore store(WorldId worldId);

    default ZipBackupStore defaultStore() {
        throw new IllegalStateException("No default ZIP store is available");
    }
}
