package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupManifest;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A private copy of a world that every enabled destination backs up; destinations only read it.
 * The inventory lists each file's size and SHA-256, so a destination can use them without hashing
 * again; the manifest's counts and digests are derived from it.
 */
public record BackupCapture(Path worldDirectory, BackupManifest manifest, WorldInventory inventory) {
    public BackupCapture {
        worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(inventory, "inventory");
    }
}
