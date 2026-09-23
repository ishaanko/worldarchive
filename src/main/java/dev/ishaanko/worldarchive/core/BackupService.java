package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Lists, restores, deletes, verifies, and syncs backups. The recovery service implements it, and
 * the client runtime wraps it for the screens. Backups are created through
 * {@link SerializedBackupCoordinator} instead.
 *
 * <p>Implementations must be thread-safe. Returned stages must never depend on a render or server
 * thread continuing to execute blocking storage work.</p>
 */
public interface BackupService {
    CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId);

    CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener);

    /**
     * Deletes the backups the player confirmed, one or many, as one operation. Results arrive in
     * request order; a backup that could not be deleted, or that changed since it was confirmed,
     * is reported as failed instead of failing the others.
     */
    CompletionStage<List<BackupResult>> deleteBackups(
            List<DeleteBackupRequest> requests,
            ProgressListener progressListener);

    CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener);

    CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener);
}
