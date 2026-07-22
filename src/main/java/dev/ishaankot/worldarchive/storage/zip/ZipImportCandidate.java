package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Fully inspected WorldArchive ZIP discovered outside managed storage. */
public record ZipImportCandidate(
        BackupManifest manifest,
        Path archivePath,
        String archiveSha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ZipImportCandidate {
        Objects.requireNonNull(manifest, "manifest");
        archivePath = Objects.requireNonNull(archivePath, "archivePath")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(archiveSha256, "archiveSha256");
        if (!SHA256.matcher(archiveSha256).matches()) {
            throw new IllegalArgumentException("ZIP import fingerprint must be lowercase SHA-256");
        }
    }
}
