package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.importing.ImportSourceRegistry;
import dev.ishaanko.worldarchive.core.BackupMaintenanceService;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.core.DeletePreparation;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Thread-safe, Minecraft-independent implementation of all non-create backup operations. */
public final class BackupRecoveryService implements BackupMaintenanceService {
    public static final Duration DEFAULT_CONFIRMATION_LIFETIME = Duration.ofMinutes(1);

    private static final Duration MAXIMUM_CONFIRMATION_LIFETIME = Duration.ofMinutes(5);

    private final BackupCatalog catalog;

    private final Executor executor;

    private final RecoveryRestoreOperation restoreOperation;

    private final RecoveryDeleteOperation deleteOperation;

    private final RecoveryHealthOperations healthOperations;

    public BackupRecoveryService(
            BackupCatalog catalog,
            Optional<? extends GitSnapshotStore> gitBackend,
            Optional<? extends ZipBackupStoreResolver> zipStore,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            WorldOperationGate operationGate) {
        this(
                catalog,
                RecoveryDestinations.create(gitBackend, zipStore, Optional.empty(), Clock.systemUTC()),
                BackupDeletionRegistry.NONE,
                identityStore,
                metadataFinalizer,
                executor,
                Clock.systemUTC(),
                DEFAULT_CONFIRMATION_LIFETIME,
                operationGate,
                Files::move);
    }

    public BackupRecoveryService(
            BackupCatalog catalog,
            Optional<? extends GitSnapshotStore> gitBackend,
            Optional<? extends ZipBackupStoreResolver> zipStore,
            ImportSourceRegistry sources,
            BackupDeletionRegistry deletions,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            WorldOperationGate operationGate) {
        this(
                catalog,
                gitBackend,
                zipStore,
                sources,
                deletions,
                identityStore,
                metadataFinalizer,
                executor,
                operationGate,
                Clock.systemUTC());
    }

    public BackupRecoveryService(
            BackupCatalog catalog,
            Optional<? extends GitSnapshotStore> gitBackend,
            Optional<? extends ZipBackupStoreResolver> zipStore,
            ImportSourceRegistry sources,
            BackupDeletionRegistry deletions,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            WorldOperationGate operationGate,
            Clock clock) {
        this(
                catalog,
                RecoveryDestinations.create(
                        gitBackend,
                        zipStore,
                        Optional.of(Objects.requireNonNull(sources, "sources")),
                        clock),
                deletions,
                identityStore,
                metadataFinalizer,
                executor,
                clock,
                DEFAULT_CONFIRMATION_LIFETIME,
                operationGate,
                Files::move);
    }

    BackupRecoveryService(
            BackupCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            Clock clock,
            Duration confirmationLifetime,
            WorldOperationGate operationGate) {
        this(
                catalog,
                RecoveryDestinations.of(destinations),
                BackupDeletionRegistry.NONE,
                identityStore,
                metadataFinalizer,
                executor,
                clock,
                confirmationLifetime,
                operationGate,
                Files::move);
    }

    BackupRecoveryService(
            BackupCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            BackupDeletionRegistry deletions,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            Clock clock,
            Duration confirmationLifetime,
            WorldOperationGate operationGate,
            DirectoryMove directoryMove) {
        this(
                catalog,
                RecoveryDestinations.of(destinations),
                deletions,
                identityStore,
                metadataFinalizer,
                executor,
                clock,
                confirmationLifetime,
                operationGate,
                directoryMove);
    }

    private BackupRecoveryService(
            BackupCatalog catalog,
            RecoveryDestinations destinations,
            BackupDeletionRegistry deletions,
            WorldIdentityStore identityStore,
            RestoredWorldMetadataFinalizer metadataFinalizer,
            Executor executor,
            Clock clock,
            Duration confirmationLifetime,
            WorldOperationGate operationGate,
            DirectoryMove directoryMove) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executor = Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(destinations, "destinations");
        Objects.requireNonNull(deletions, "deletions");
        Objects.requireNonNull(identityStore, "identityStore");
        Objects.requireNonNull(metadataFinalizer, "metadataFinalizer");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(operationGate, "operationGate");
        Objects.requireNonNull(directoryMove, "directoryMove");
        this.restoreOperation = new RecoveryRestoreOperation(
                catalog, destinations, identityStore, metadataFinalizer, operationGate, directoryMove);
        this.deleteOperation = new RecoveryDeleteOperation(
                catalog, destinations, deletions, operationGate, clock,
                requireShortLifetime(confirmationLifetime));
        this.healthOperations = new RecoveryHealthOperations(catalog, destinations, operationGate, clock);
    }

    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> worldId.isPresent()
                ? catalog.list(worldId.orElseThrow())
                : catalog.listAll());
    }

    @Override
    public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> catalog.find(backupId));
    }

    @Override
    public CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation ->
                restoreOperation.restoreBlocking(request, progressListener, cancellation));
    }

    @Override
    public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> deleteOperation.prepareDeleteBlocking(backupId));
    }

    @Override
    public CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation ->
                deleteOperation.deleteBlocking(request, progressListener, cancellation));
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation ->
                healthOperations.verifyBlocking(backupId, progressListener, cancellation));
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(cancellation ->
                healthOperations.syncBlocking(backupId, progressListener, cancellation));
    }

    @Override
    public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> healthOperations.healthBlocking(worldId));
    }

    private <T> CompletionStage<T> submit(CheckedSupplier<T> operation) {
        return submit(cancellation -> operation.get());
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

    private static Duration requireShortLifetime(Duration lifetime) {
        Objects.requireNonNull(lifetime, "confirmationLifetime");
        if (lifetime.isZero()
                || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_CONFIRMATION_LIFETIME) > 0) {
            throw new IllegalArgumentException(
                    "Delete confirmation lifetime must be positive and no longer than five minutes");
        }
        return lifetime;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface DirectoryMove extends RestoreWorkspace.DirectoryMove {
    }
}
