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

    default boolean isBusy(WorldId worldId) {
        return currentOperation(worldId).isPresent();
    }
}
