package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;

/**
 * One backup destination. The coordinator runs {@link #createBackup} for every destination on a
 * worker of its own and cancels a write by interrupting that worker.
 */
public interface BackupBackend {
    DestinationType destinationType();

    /**
     * Writes one backup of the capture and blocks until it is done. Implementations must be
     * thread-safe. A destination problem is a result, not an exception.
     *
     * <p>An interrupt asks the write to stop. A destination that already made a durable copy
     * returns it, for example a Git snapshot whose push was cut short returns PENDING_SYNC; one
     * that made nothing returns a failed result or throws {@link InterruptedException}. Any
     * other exception means no trustworthy result exists.</p>
     */
    DestinationResult createBackup(BackupCapture capture, ProgressListener progressListener)
            throws InterruptedException;
}
