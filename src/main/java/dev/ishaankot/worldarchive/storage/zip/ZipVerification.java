package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Integrity result for one ZIP archive and its sidecar. */
public record ZipVerification(
        boolean valid,
        Optional<BackupManifest> manifest,
        long verifiedFileCount,
        long verifiedByteCount,
        List<String> problems) {
    public ZipVerification {
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (verifiedFileCount < 0 || verifiedByteCount < 0) {
            throw new IllegalArgumentException("Verified counts must not be negative");
        }
        problems = List.copyOf(problems);
        if (valid != problems.isEmpty()) {
            throw new IllegalArgumentException("ZIP validity must match the problem list");
        }
        if (problems.stream().anyMatch(problem -> problem == null || problem.isBlank())) {
            throw new IllegalArgumentException("ZIP verification problems must contain text");
        }
    }
}
