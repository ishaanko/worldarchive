package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.core.BackupBackend;
import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Asynchronous ZIP destination adapter over the synchronous, worker-safe store. */
public final class ZipBackupBackend implements BackupBackend {
    private final ZipBackupStore store;

    private final Executor executor;

    public ZipBackupBackend(ZipBackupStore store, Executor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public ZipBackupBackend(java.nio.file.Path root, Executor executor) {
        this(new ZipBackupStore(root), executor);
    }

    public ZipBackupStore store() {
        return store;
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
        return CompletableFuture.supplyAsync(() -> {
            report(progressListener, progress(
                    operationId, capture, OperationPhase.PREPARING, 0, totalBytes,
                    "Preparing ZIP backup"));
            try {
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
        }, executor);
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
        try {
            listener.onProgress(progress);
        } catch (RuntimeException exception) {
            // Observers cannot turn a durable destination outcome into a false failure.
        }
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
