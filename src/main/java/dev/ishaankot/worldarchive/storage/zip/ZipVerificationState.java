package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.storage.zip.ZipArchiveInspector.Inspection;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Mutable accumulator kept private to one ZIP verification operation. */
final class ZipVerificationState {
    final LinkedHashSet<String> problems = new LinkedHashSet<>();

    private Optional<BackupManifest> manifest = Optional.empty();

    private long verifiedFiles;

    private long verifiedBytes;

    void apply(Inspection inspection) {
        manifest = inspection.manifest();
        verifiedFiles = inspection.verifiedFileCount();
        verifiedBytes = inspection.verifiedByteCount();
        problems.addAll(inspection.problems());
    }

    ZipVerification finish() {
        List<String> immutableProblems = List.copyOf(problems);
        return new ZipVerification(
                immutableProblems.isEmpty(),
                manifest,
                verifiedFiles,
                verifiedBytes,
                immutableProblems);
    }
}

record ChecksumSnapshot(String sha256, FileFingerprint fingerprint) {
}

record FileFingerprint(
        Object fileKey,
        long size,
        FileTime lastModifiedTime,
        FileTime creationTime) {
}
