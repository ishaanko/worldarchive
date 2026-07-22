package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.util.Objects;

/** A metadata-validated WorldArchive commit pinned in a private fetched repository. */
public record GitImportCandidate(
        BackupManifest manifest,
        String sourceRef,
        String commitId) {
    public GitImportCandidate {
        Objects.requireNonNull(manifest, "manifest");
        sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        commitId = GitImportValidation.objectId(commitId);
    }
}
