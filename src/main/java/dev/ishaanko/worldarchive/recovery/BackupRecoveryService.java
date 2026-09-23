package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.core.BackupService;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.importing.FileImportSourceRegistry;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Lists, restores, deletes, verifies and syncs backups, on worker threads, with no Minecraft
 * code. Restore, delete, verify and sync return a {@link CancellableTask}: cancelling it stops the
 * operation at its next safe point and completes it once the work has stopped.
 */
public final class BackupRecoveryService implements BackupService {
    private final BackupCatalog catalog;

    private final Executor executor;

    private final RecoveryRestoreOperation restoreOperation;

    private final RecoveryDeleteOperation deleteOperation;

    private final RecoveryHealthOperations healthOperations;

    public BackupRecoveryService(
            BackupCatalog catalog,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            FileImportSourceRegistry importSources,
            FileBackupDeletionRegistry deletions,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            WorldOperationGate operationGate,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executor = Objects.requireNonNull(executor, "executor");
        GitRecoveryDestination gitCopies = new GitRecoveryDestination(git, importSources);
        Map<DestinationType, RecoveryDestination> destinations = new EnumMap<>(DestinationType.class);
        destinations.put(DestinationType.GIT, gitCopies);
        destinations.put(DestinationType.ZIP, new ZipRecoveryDestination(zipStores));
        this.restoreOperation = new RecoveryRestoreOperation(
                catalog, destinations, identityStore, metadataFinalizer, operationGate, clock);
        this.deleteOperation = new RecoveryDeleteOperation(catalog, destinations, deletions, operationGate, clock);
        this.healthOperations = new RecoveryHealthOperations(catalog, destinations, gitCopies, operationGate);
    }

    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supplyChecked(executor, () -> worldId.isPresent()
                ? catalog.list(worldId.get())
                : catalog.listAll());
    }

    @Override
    public CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(task -> restoreOperation.restore(request, progressListener, task));
    }

    @Override
    public CompletionStage<List<BackupResult>> deleteBackups(
            List<DeleteBackupRequest> requests,
            ProgressListener progressListener) {
        List<DeleteBackupRequest> copy = List.copyOf(requests);
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(task -> deleteOperation.delete(copy, progressListener, task));
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(task -> healthOperations.verify(backupId, progressListener, task));
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(task -> healthOperations.sync(backupId, progressListener, task));
    }

    private <T> CompletionStage<T> submit(CancellableTask.Operation<T> operation) {
        CancellableTask<T> task = new CancellableTask<>(operation);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.completeExceptionally(exception);
        }
        return task;
    }
}
