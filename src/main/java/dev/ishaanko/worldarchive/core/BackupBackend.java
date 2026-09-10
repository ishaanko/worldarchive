package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.util.concurrent.CompletionStage;

/**
 * Independent asynchronous backup destination.
 *
 * <p>Implementations must be thread-safe, must not perform blocking work on the calling thread,
 * and must complete with a result for recoverable destination failures. Exceptional completion is
 * reserved for programming errors or failures that prevent a trustworthy destination result.</p>
 *
 * <p>Return the stage from {@link AsyncTasks#supplyInterruptible} directly, not a stage
 * composed from it. The coordinator stops a cancelled write through that future so the
 * destination's own outcome, such as a snapshot published before an interrupted push, is
 * still recorded. Any other stage is cancelled outright and its outcome is lost.</p>
 */
public interface BackupBackend {
    DestinationType destinationType();

    CompletionStage<DestinationResult> createBackup(BackupCapture capture, ProgressListener progressListener);
}
