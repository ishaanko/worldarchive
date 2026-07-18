package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.nio.file.Path;
import java.util.Objects;

/** A flushed source tree and the manifest shared by all enabled destinations. */
public record BackupCapture(Path worldDirectory, BackupManifest manifest) {
    public BackupCapture {
        worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(manifest, "manifest");
    }
}
