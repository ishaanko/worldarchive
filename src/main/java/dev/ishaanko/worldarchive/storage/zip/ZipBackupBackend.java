package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.core.AsyncTasks;
import dev.ishaanko.worldarchive.core.BackupBackend;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.BackupOperation;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.OperationPhase;
import dev.ishaanko.worldarchive.core.Observers;
import dev.ishaanko.worldarchive.core.OperationProgress;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Asynchronous ZIP destination adapter over the synchronous, worker-safe store. */
public final class ZipBackupBackend implements BackupBackend {
    private final ZipBackupStoreResolver stores;

    private final Executor executor;

    public ZipBackupBackend(ZipBackupStore store, Executor executor) {
        this((ZipBackupStoreResolver) store, executor);
    }

    public ZipBackupBackend(ZipBackupStoreResolver stores, Executor executor) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public ZipBackupBackend(java.nio.file.Path root, Executor executor) {
        this(new ZipBackupStore(root), executor);
    }

    public ZipBackupStore store() {
        if (stores instanceof ZipBackupStore store) {
            return store;
        }
        throw new IllegalStateException("This ZIP backend uses per-world stores");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.ZIP;
    }

    @Override
    public CompletionStage<DestinationResult> createBackup(
            BackupCapture capture,
            ProgressListener progressListener) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(progressListener, "progressListener");
        OperationId operationId = OperationId.create();
        long totalBytes = capture.manifest().sourceByteCount();
        // Interruptible, so a cancelled backup stops the writer; the store then removes
        // its own partial files while it unwinds.
        return AsyncTasks.supplyInterruptibly(executor, () -> {
            report(progressListener, progress(
                    operationId, capture, OperationPhase.PREPARING, 0, totalBytes,
                    "Preparing ZIP backup"));
            try {
                ZipBackupStore store = stores.store(capture.manifest().worldId());
                ZipBackupArtifact artifact = store.create(capture, completed -> report(
                        progressListener,
                        progress(operationId, capture, OperationPhase.WRITING,
                                boundedProgress(completed, totalBytes), totalBytes,
                                "Writing ZIP backup")));
                report(progressListener, progress(
                        operationId, capture, OperationPhase.COMPLETE, totalBytes, totalBytes,
                        "ZIP backup complete"));
                return DestinationResult.success(DestinationType.ZIP, artifact.artifactId())
                        .withVerification(VerificationStatus.VERIFIED);
            } catch (IOException | SecurityException exception) {
                report(progressListener, progress(
                        operationId, capture, OperationPhase.FAILED, 0, totalBytes,
                        "ZIP backup failed"));
                return DestinationResult.failed(DestinationType.ZIP, safeFailure(exception));
            }
        });
    }

    @Override
    public CompletionStage<Boolean> discardBackup(BackupManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        // Not interruptible: the rollback of a cancelled backup must run to completion.
        return AsyncTasks.supply(executor, () -> {
            try {
                ZipBackupStore store = stores.store(manifest.worldId());
                Path archive = store.root()
                        .resolve(manifest.worldId().toString())
                        .resolve(ZipBackupStore.archiveFilename(manifest))
                        .normalize();
                store.delete(archive);
                // An already-absent pair also means the destination no longer holds it.
                return true;
            } catch (IOException | SecurityException exception) {
                throw new CompletionException(
                        "Cancelled ZIP backup could not be removed", exception);
            }
        });
    }

    private static OperationProgress progress(
            OperationId operationId,
            BackupCapture capture,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(
                operationId,
                capture.manifest().worldId(),
                Optional.of(capture.manifest().backupId()),
                BackupOperation.CREATE,
                phase,
                completed,
                total,
                message);
    }

    private static void report(ProgressListener listener, OperationProgress progress) {
        Observers.safely(() -> listener.onProgress(progress));
    }

    private static long boundedProgress(long completed, long total) {
        if (total == 0) {
            return completed;
        }
        return Math.min(completed, total);
    }

    private static String safeFailure(Exception exception) {
        if (exception instanceof ZipBackupException) {
            return exception.getMessage();
        }
        if (exception instanceof AccessDeniedException) {
            return "ZIP destination denied filesystem access";
        }
        if (exception instanceof FileSystemException) {
            return "ZIP destination filesystem operation failed";
        }
        return "ZIP backup could not be completed";
    }
}
