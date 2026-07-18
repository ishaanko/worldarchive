package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
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
}
