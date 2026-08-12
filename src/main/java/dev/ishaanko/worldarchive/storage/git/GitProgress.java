package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.core.BackupOperation;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.OperationPhase;
import dev.ishaanko.worldarchive.core.OperationProgress;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.model.BackupManifest;
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
