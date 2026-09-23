package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.model.BackupManifest;
import java.nio.file.Path;
import java.util.Objects;

/** A published archive of one backup; its checksum file sits next to it. */
public record ZipBackupArtifact(BackupManifest manifest, Path archivePath) {
    public ZipBackupArtifact {
        Objects.requireNonNull(manifest, "manifest");
        archivePath = Objects.requireNonNull(archivePath, "archivePath").toAbsolutePath().normalize();
    }

    /** The {@code <world id>/<file name>} under which the catalog records the archive. */
    public String artifactId() {
        return manifest.worldId() + "/" + archivePath.getFileName();
    }
}
