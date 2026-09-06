package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Non-create operations composed with the create coordinator. */
public interface BackupMaintenanceService {
    CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId);

    CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId);

    CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener);

    CompletionStage<DeletePreparation> prepareDelete(BackupId backupId);

    CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener);

    /**
     * Deletes several confirmed backups as one operation. Results arrive in request order;
     * a backup that could not be deleted is reported as failed instead of failing the batch.
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

    CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId);
}
