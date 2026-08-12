package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Shared asynchronous API used by commands, screens, and lifecycle integration.
 *
 * <p>Implementations must be thread-safe. Returned stages must never depend on a render or server
 * thread continuing to execute blocking storage work.</p>
 */
public interface BackupService {
    CompletionStage<BackupResult> createBackup(
            CreateBackupRequest request,
            ProgressListener progressListener);

    CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId);

    CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId);

    CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener);

    CompletionStage<DeletePreparation> prepareDelete(BackupId backupId);

    CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener);

    CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener);

    CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener);

    CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId);
}
