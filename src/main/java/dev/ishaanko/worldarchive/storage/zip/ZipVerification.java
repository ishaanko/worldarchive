package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.model.BackupManifest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a full read of one archive found. A problem means the archive is damaged or is not the
 * backup its name says; a warning, such as a missing checksum file, leaves the backup usable.
 * The manifest is present once the archive's own manifest could be read.
 */
public record ZipVerification(Optional<BackupManifest> manifest, List<String> problems, List<String> warnings) {
    public ZipVerification {
        Objects.requireNonNull(manifest, "manifest");
        problems = List.copyOf(problems);
        warnings = List.copyOf(warnings);
    }

    static ZipVerification failed(String problem) {
        return new ZipVerification(Optional.empty(), List.of(problem), List.of());
    }

    public boolean valid() {
        return problems.isEmpty();
    }
}
