package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.model.BackupId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One archive as storage accounting sees it: the backup ID from its file name, the archive,
 * and its bytes on disk with the checksum file, from file sizes alone.
 */
public record ZipArchiveSize(BackupId backupId, Path archivePath, long bytes) {
    public ZipArchiveSize {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(archivePath, "archivePath");
    }
}
