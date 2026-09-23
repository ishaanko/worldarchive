package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import dev.ishaanko.worldarchive.support.Observers;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Small helpers shared by the recovery operations. */
final class RecoverySupport {
    private RecoverySupport() {
    }

    static BackupRecord requireRecord(BackupCatalog catalog, BackupId backupId) throws IOException {
        return catalog.find(backupId).orElseThrow(() -> new BackupRecoveryException(
                "This backup is no longer in the backup list. Open the list again."));
    }

    static void requireSameManifest(BackupRecord expected, BackupRecord current) {
        if (!expected.manifest().equals(current.manifest())) {
            throw new BackupRecoveryException("The backup changed while the operation waited. Try again.");
        }
    }

    /** The destinations that hold a copy, Git before ZIP. */
    static List<DestinationResult> copies(BackupRecord record) {
        return record.result().destinations().stream()
                .filter(DestinationResult::isDurable)
                .sorted(Comparator.comparing(DestinationResult::destination))
                .toList();
    }

    static Optional<DestinationResult> copy(BackupRecord record, DestinationType type) {
        return copies(record).stream().filter(copy -> copy.destination() == type).findFirst();
    }

    /** The record with its destinations replaced, keeping its completion time. */
    static BackupRecord withDestinations(BackupRecord record, List<DestinationResult> destinations) {
        return new BackupRecord(record.manifest(), new BackupResult(
                record.manifest().backupId(),
                record.manifest().worldId(),
                destinations,
                record.result().completedAt()));
    }

    /**
     * Waits for a storage operation even when this thread is interrupted: the operation is then
     * asked to stop, and its outcome is still awaited, so nothing it writes outlives the wait. The
     * interrupt is restored afterwards.
     */
    static <T> T awaitStopped(CompletionStage<T> stage) throws Exception {
        CompletableFuture<T> future = stage.toCompletableFuture();
        try {
            return AsyncTasks.await(future);
        } catch (InterruptedException interrupted) {
            if (future instanceof AsyncTasks.InterruptibleFuture<T> running) {
                running.stop(true);
            }
            try {
                return future.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw failure;
            } finally {
                Thread.currentThread().interrupt();
            }
        }
    }

    static OperationProgress progress(
            OperationId operationId,
            BackupRecord record,
            BackupOperation operation,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(operationId, record.manifest().worldId(), Optional.of(record.manifest().backupId()),
                operation, phase, completed, total, message);
    }

    static OperationProgress worldProgress(
            OperationId operationId,
            WorldId worldId,
            BackupOperation operation,
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(operationId, worldId, Optional.empty(), operation, phase, completed, total, message);
    }

    /** Tells the listener; a listener that throws never changes the outcome. */
    static void report(ProgressListener listener, OperationProgress progress) {
        Observers.safely(() -> listener.onProgress(progress));
    }
}
