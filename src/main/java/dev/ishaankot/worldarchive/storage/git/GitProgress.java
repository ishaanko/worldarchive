package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupManifest;
import java.util.Optional;

/** Reports Git backup progress without allowing observers to affect storage integrity. */
final class GitProgress {
    private GitProgress() {}

    static void report(
            ProgressListener listener,
            OperationId operationId,
            BackupManifest manifest,
            OperationPhase phase,
            String message) {
        try {
            listener.onProgress(new OperationProgress(
                    operationId,
                    manifest.worldId(),
                    Optional.of(manifest.backupId()),
                    BackupOperation.CREATE,
                    phase,
                    0,
                    0,
                    message));
        } catch (RuntimeException ignored) {
            // Storage integrity must not depend on an observer.
        }
    }
}
