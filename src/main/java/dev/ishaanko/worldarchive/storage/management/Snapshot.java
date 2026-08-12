package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Point-in-time measurement of a world's managed storage, bound to a fingerprint. */
record Snapshot(
        WorldConfig world,
        List<BackupRecord> records,
        ZipBackupStore zipStore,
        Map<BackupId, ZipBackupArtifact> zipArtifacts,
        Map<BackupId, GitSnapshot> localGitSnapshots,
        long gitBytes,
        long zipBytes,
        boolean unmeteredStoragePresent,
        String fingerprint) {
    Snapshot {
        Objects.requireNonNull(world, "world");
        records = List.copyOf(records);
        Objects.requireNonNull(zipStore, "zipStore");
        zipArtifacts = Map.copyOf(zipArtifacts);
        localGitSnapshots = Map.copyOf(localGitSnapshots);
        if (gitBytes < 0 || zipBytes < 0) {
            throw new IllegalArgumentException("Snapshot sizes must not be negative");
        }
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    long totalBytes() {
        return Math.addExact(gitBytes, zipBytes);
    }
}
