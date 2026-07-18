package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** A completely published ZIP archive and its integrity sidecar. */
public record ZipBackupArtifact(
        BackupManifest manifest,
        Path archivePath,
        Path checksumPath,
        String archiveSha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ZipBackupArtifact {
        Objects.requireNonNull(manifest, "manifest");
        archivePath = Objects.requireNonNull(archivePath, "archivePath")
                .toAbsolutePath()
                .normalize();
        checksumPath = Objects.requireNonNull(checksumPath, "checksumPath")
                .toAbsolutePath()
                .normalize();
        if (!Objects.equals(archivePath.getParent(), checksumPath.getParent())) {
            throw new IllegalArgumentException("ZIP archive and checksum must share a directory");
        }
        if (!checksumPath.getFileName().toString()
                .equals(archivePath.getFileName().toString() + ".sha256")) {
            throw new IllegalArgumentException("ZIP checksum name does not match its archive");
        }
        Objects.requireNonNull(archiveSha256, "archiveSha256");
        if (!SHA256.matcher(archiveSha256).matches()) {
            throw new IllegalArgumentException("ZIP checksum must be lowercase SHA-256");
        }
    }

    /** Stable destination identifier suitable for persistence in a destination result. */
    public String artifactId() {
        return manifest.worldId() + "/" + archivePath.getFileName();
    }
}
