package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupManifest;
import java.util.Objects;

/** A WorldArchive snapshot commit found by an import preview and pinned in its private repository. */
public record GitImportCandidate(
        BackupManifest manifest,
        String sourceRef,
        String commitId) {
    public GitImportCandidate {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(commitId, "commitId");
        if (!GitRepository.isObjectId(commitId)) {
            throw new IllegalArgumentException("Imported Git commit is not a SHA-1 object ID");
        }
    }
}
