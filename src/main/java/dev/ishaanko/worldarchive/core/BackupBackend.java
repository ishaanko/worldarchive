package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.util.concurrent.CompletionStage;

/**
 * Independent asynchronous backup destination.
 *
 * <p>Implementations must be thread-safe, must not perform blocking work on the calling thread,
 * and must complete with a result for recoverable destination failures. Exceptional completion is
 * reserved for programming errors or failures that prevent a trustworthy destination result.</p>
 */
public interface BackupBackend {
    DestinationType destinationType();

    CompletionStage<DestinationResult> createBackup(BackupCapture capture, ProgressListener progressListener);

    /**
     * Removes whatever this destination holds for one cancelled create operation.
     *
     * <p>The coordinator calls this only for the backup a user just cancelled, identified by the
     * manifest's world and backup id. The call must be idempotent, must remove at most that one
     * artifact, and must complete with {@code true} only when the destination no longer holds it
     * (an artifact that never existed also counts). It runs after the interrupted create has been
     * asked to stop, and implementations must serialize behind their own create path.</p>
     */
    CompletionStage<Boolean> discardBackup(BackupManifest manifest);
}
