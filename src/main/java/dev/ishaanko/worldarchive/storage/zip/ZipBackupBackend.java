package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.core.BackupBackend;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.support.Observers;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * The ZIP destination of a backup: writes the archive on the calling thread into the store of
 * the backup's world. The archive is read back before it is published, so a success is recorded
 * as verified.
 */
public final class ZipBackupBackend implements BackupBackend {
    private final ZipBackupStoreResolver stores;

    public ZipBackupBackend(ZipBackupStoreResolver stores) {
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.ZIP;
    }

    /** An interrupt stops the write; the store removes the partial archive and this throws. */
    @Override
    public DestinationResult createBackup(BackupCapture capture, ProgressListener progressListener)
            throws InterruptedException {
        Progress progress = new Progress(capture, Objects.requireNonNull(progressListener, "progressListener"));
        progress.report(OperationPhase.PREPARING, 0, "Preparing ZIP backup");
        try {
            ZipBackupArtifact artifact = stores.store(capture.manifest().worldId()).create(capture, progress::writing);
            progress.report(OperationPhase.COMPLETE, progress.total, "ZIP backup complete");
            return DestinationResult.success(DestinationType.ZIP, artifact.artifactId())
                    .withVerification(VerificationStatus.VERIFIED);
        } catch (IOException exception) {
            if (Thread.interrupted()) {
                InterruptedException cancelled = new InterruptedException("The ZIP backup was cancelled");
                cancelled.initCause(exception);
                throw cancelled;
            }
            progress.report(OperationPhase.FAILED, 0, "ZIP backup failed");
            return DestinationResult.failed(DestinationType.ZIP, failureMessage(exception));
        }
    }

    private static String failureMessage(IOException exception) {
        if (exception instanceof ZipBackupException) {
            return exception.getMessage();
        }
        return "The ZIP backup could not be written (" + ZipBackupException.reason(exception) + ").";
    }

    /** Reports the progress of one write, once per whole percent. */
    private static final class Progress {
        private final BackupCapture capture;

        private final ProgressListener listener;

        private final OperationId operationId = OperationId.create();

        private final long total;

        private long reportedPercent = -1;

        private Progress(BackupCapture capture, ProgressListener listener) {
            this.capture = capture;
            this.listener = listener;
            this.total = capture.manifest().sourceByteCount();
        }

        private void writing(long completed) {
            long done = Math.min(completed, total);
            long percent = total == 0 ? 100 : done * 100 / total;
            if (percent != reportedPercent) {
                reportedPercent = percent;
                report(OperationPhase.WRITING, done, "Writing ZIP backup");
            }
        }

        private void report(OperationPhase phase, long completed, String message) {
            OperationProgress progress = new OperationProgress(
                    operationId,
                    capture.manifest().worldId(),
                    Optional.of(capture.manifest().backupId()),
                    BackupOperation.CREATE,
                    phase,
                    completed,
                    total,
                    message);
            Observers.safely(() -> listener.onProgress(progress));
        }
    }
}
