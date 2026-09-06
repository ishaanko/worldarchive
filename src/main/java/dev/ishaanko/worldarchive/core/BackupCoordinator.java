package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Serialized lifecycle-facing backup service.
 *
 * <p>Implementations coalesce compatible concurrent triggers for the same world, serialize
 * incompatible operations, and allow different worlds to proceed independently.</p>
 */
public interface BackupCoordinator {
    CompletionStage<BackupResult> createBackup(
            CreateBackupRequest request,
            ProgressListener progressListener);

    /** Captures synchronously on the calling thread, suitable for an integrated-server save hook. */
    PreparedBackup prepareCapture(
            CreateBackupRequest request,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException;

    /** Transfers an already captured source into the serialized destination queue. */
    CompletionStage<BackupResult> createPreparedBackup(
            PreparedBackup preparedBackup,
            ProgressListener progressListener);

    Optional<OperationProgress> currentOperation(WorldId worldId);

    /**
     * Requests that one running create operation stop and remove whatever it already wrote.
     *
     * <p>Returns {@code true} when the request was accepted; the operation's stage then settles
     * once the rollback is done. Returns {@code false} when the operation is unknown, already
     * finalizing, or already asked to cancel.</p>
     */
    boolean cancelBackup(OperationId operationId);

    default boolean isBusy(WorldId worldId) {
        return currentOperation(worldId).isPresent();
    }
}
