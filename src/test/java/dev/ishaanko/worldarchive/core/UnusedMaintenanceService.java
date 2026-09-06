package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Maintenance service stub for tests that only exercise the create path. */
final class UnusedMaintenanceService implements BackupMaintenanceService {
    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }
}
