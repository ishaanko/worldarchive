package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.zip.ZipArchiveSize;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One world's managed storage at one moment: the catalog's records, and the copies that are on
 * this computer. Cleanup decides from the copies on disk, never from the catalog alone, and
 * applies a plan only while the fingerprint of a new snapshot still matches the preview's.
 *
 * @param zipArchives the world's archives by backup, from their file names and sizes
 * @param localGitSnapshots the world's Git snapshots on this computer, by backup
 * @param unmeteredStoragePresent whether some copies are stored where this snapshot cannot measure
 */
record Snapshot(
        WorldConfig world,
        List<BackupRecord> records,
        ZipBackupStore zipStore,
        Map<BackupId, ZipArchiveSize> zipArchives,
        Map<BackupId, GitSnapshot> localGitSnapshots,
        long gitBytes,
        long zipBytes,
        boolean unmeteredStoragePresent,
        String fingerprint) {
    Snapshot {
        Objects.requireNonNull(world, "world");
        records = List.copyOf(records);
        Objects.requireNonNull(zipStore, "zipStore");
        zipArchives = Map.copyOf(zipArchives);
        localGitSnapshots = Map.copyOf(localGitSnapshots);
        if (gitBytes < 0 || zipBytes < 0) {
            throw new IllegalArgumentException("Snapshot sizes must not be negative");
        }
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    long totalBytes() {
        return Math.addExact(gitBytes, zipBytes);
    }

    Optional<BackupRecord> record(BackupId backupId) {
        return records.stream().filter(record -> record.manifest().backupId().equals(backupId)).findFirst();
    }

    /** The catalog's name of this backup's archive on disk, {@code <world id>/<file name>}. */
    Optional<String> zipArtifactId(BackupId backupId) {
        return Optional.ofNullable(zipArchives.get(backupId))
                .map(archive -> world.worldId() + "/" + archive.archivePath().getFileName());
    }
}
