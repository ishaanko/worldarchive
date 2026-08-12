package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.core.BackupCoordinator;
import dev.ishaanko.worldarchive.core.CaptureProgressListener;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.OperationProgress;
import dev.ishaanko.worldarchive.core.PreparedBackup;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Stable coordinator facade over atomically replaceable runtime service graphs. */
final class RuntimeBackupCoordinator implements BackupCoordinator {
    private final WorldArchiveRuntime runtime;

    RuntimeBackupCoordinator(WorldArchiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public PreparedBackup prepareCapture(
            CreateBackupRequest request,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException {
        RuntimeConfigurationGate.Permit permit = runtime.configurationGate().enterBackup();
        boolean transferred = false;
        try {
            RuntimeState state = runtime.states().currentOrNull();
            if (state == null || runtime.isClosed()) {
                throw new IOException("WorldArchive is still loading");
            }
            if (!runtime.registerWorldPath(
                    request.worldId(),
                    request.worldDirectory(),
                    state)) {
                throw new IOException(
                        "The world identity is registered to a different folder");
            }
            Optional<String> storageIssue = runtime.storageIssue(state);
            if (storageIssue.isPresent()) {
                throw new IOException(storageIssue.orElseThrow());
            }
            PreparedBackup prepared = state.coordinator().prepareCapture(
                    request,
                    progressListener);
            PreparedOwnership ownership = new PreparedOwnership(state, permit);
            if (runtime.preparedCaptures().putIfAbsent(prepared, ownership) != null) {
                prepared.close();
                throw new IOException("Prepared capture is already registered");
            }
            try {
                prepared.addReleaseObserver(
                        () -> releaseAbandonedPrepared(prepared, ownership));
            } catch (RuntimeException | Error exception) {
                runtime.preparedCaptures().remove(prepared, ownership);
                try {
                    prepared.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
            transferred = true;
            return prepared;
        } finally {
            if (!transferred) {
                permit.close();
            }
        }
    }

    @Override
    public CompletionStage<BackupResult> createPreparedBackup(
            PreparedBackup preparedBackup,
            ProgressListener progressListener) {
        Objects.requireNonNull(preparedBackup, "preparedBackup");
        Objects.requireNonNull(progressListener, "progressListener");
        PreparedOwnership ownership =
                runtime.preparedCaptures().remove(preparedBackup);
        if (ownership == null) {
            return failedStage("Prepared capture does not belong to this runtime");
        }
        Optional<String> storageIssue = runtime.storageIssue(ownership.state());
        if (storageIssue.isPresent()) {
            try {
                preparedBackup.close();
            } catch (IOException exception) {
                ownership.permit().close();
                return CompletableFuture.failedFuture(exception);
            }
            ownership.permit().close();
            return failedStage(storageIssue.orElseThrow());
        }
        try {
            CompletionStage<BackupResult> stage = ownership.state()
                    .coordinator()
                    .createPreparedBackup(preparedBackup, progressListener);
            stage.whenComplete((ignored, throwable) -> ownership.permit().close());
            return stage;
        } catch (RuntimeException | Error exception) {
            ownership.permit().close();
            throw exception;
        }
    }

    @Override
    public CompletionStage<BackupResult> createBackup(
            CreateBackupRequest request,
            ProgressListener progressListener) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            if (state == null || runtime.isClosed()) {
                return failedStage("WorldArchive is still loading");
            }
            if (!runtime.registerWorldPath(
                    request.worldId(),
                    request.worldDirectory(),
                    state)) {
                return failedStage(
                        "The world identity is registered to a different folder");
            }
            Optional<String> storageIssue = runtime.storageIssue(state);
            if (storageIssue.isPresent()) {
                return failedStage(storageIssue.orElseThrow());
            }
            return state.coordinator().createBackup(request, progressListener);
        });
    }

    @Override
    public Optional<OperationProgress> currentOperation(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        if (runtime.isClosed()) {
            return Optional.empty();
        }
        RuntimeState current = runtime.states().currentOrNull();
        if (current != null) {
            Optional<OperationProgress> active = current.coordinator()
                    .currentOperation(worldId);
            if (active.isPresent()) {
                return active;
            }
        }
        return runtime.states().retained().stream()
                .filter(state -> state != current)
                .map(state -> state.coordinator().currentOperation(worldId))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private void releaseAbandonedPrepared(
            PreparedBackup prepared,
            PreparedOwnership ownership) {
        if (runtime.preparedCaptures().remove(prepared, ownership)) {
            ownership.permit().close();
        }
    }

    private static <T> CompletionStage<T> failedStage(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }
}
