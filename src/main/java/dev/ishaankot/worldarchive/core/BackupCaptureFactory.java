package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/** Synchronously prepares one immutable source tree while its caller holds the capture gate. */
public interface BackupCaptureFactory {
    CapturedBackup capture(
            CreateBackupRequest request,
            BackupId backupId,
            Instant createdAt,
            Optional<WorldInventory> previousInventory,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException;
}
