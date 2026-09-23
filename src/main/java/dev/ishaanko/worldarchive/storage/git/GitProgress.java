package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.support.Observers;
import java.util.Objects;
import java.util.Optional;

/** Reports the phases of one Git backup; a failing listener never affects the backup. */
final class GitProgress {
    private final ProgressListener listener;

    private final OperationId operationId;

    private final BackupManifest manifest;

    GitProgress(ProgressListener listener, BackupManifest manifest) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.operationId = OperationId.create();
    }

    void report(OperationPhase phase, String message) {
        Observers.safely(() -> listener.onProgress(new OperationProgress(
                operationId,
                manifest.worldId(),
                Optional.of(manifest.backupId()),
                BackupOperation.CREATE,
                phase,
                0,
                0,
                message)));
    }
}
