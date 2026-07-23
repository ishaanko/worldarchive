package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.core.BackupCoordinator;
import dev.ishaankot.worldarchive.core.CaptureProgressListener;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.PreparedBackup;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupRequest;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            return state == null || runtime.isClosed()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().listBackups(worldId);
        });
    }

    @Override
    public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            return state == null || runtime.isClosed()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().findBackup(backupId);
        });
    }

    @Override
    public CompletionStage<RestoreBackupResult> restoreBackup(
            RestoreBackupRequest request,
            ProgressListener progressListener) {
        RuntimeConfigurationGate.Permit operationPermit =
                runtime.configurationGate().enterBackup();
            RuntimeState state = runtime.states().currentOrNull();
        if (state == null || runtime.isClosed()) {
            operationPermit.close();
            return failedStage("WorldArchive is still loading");
        }
        Optional<String> storageIssue = runtime.storageIssue(state);
        if (storageIssue.isPresent()) {
            operationPermit.close();
            return failedStage(storageIssue.orElseThrow());
        }
        CompletionStage<RestoreBackupResult> operation;
        try {
            operation = state.coordinator().restoreBackup(request, progressListener);
        } catch (RuntimeException | Error exception) {
            operationPermit.close();
            throw exception;
        }
        return registerRestoredWorld(operation, operationPermit);
    }

    @Override
    public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            return state == null || runtime.isClosed()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().prepareDelete(backupId);
        });
    }

    @Override
    public CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener) {
        return withHealthyState(state -> state.coordinator()
                .deleteBackup(request, progressListener));
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return withHealthyState(state -> state.coordinator()
                .verifyBackup(backupId, progressListener));
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return withHealthyState(state -> state.coordinator()
                .syncBackup(backupId, progressListener));
    }

    @Override
    public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            if (state == null || runtime.isClosed()) {
                return failedStage("WorldArchive is still loading");
            }
            if (runtime.storageIssue(state).isPresent()) {
                return CompletableFuture.completedFuture(runtime.storageAwareHealth(
                        state.config(),
                        runtime.disabledHealth(state.config())));
            }
            if (!state.config().git().enabled() && !state.config().zip().enabled()) {
                return CompletableFuture.completedFuture(
                        runtime.disabledHealth(state.config()));
            }
            return state.coordinator().health(worldId).thenApply(health -> {
                health.stream()
                        .filter(item -> item.destination() == DestinationType.GIT)
                        .findFirst()
                        .ifPresent(item -> updateGitAvailability(state, item));
                return runtime.storageAwareHealth(
                        state.config(),
                        runtime.configuredHealth(state.config(), health));
            });
        });
    }

    private void releaseAbandonedPrepared(
            PreparedBackup prepared,
            PreparedOwnership ownership) {
        if (runtime.preparedCaptures().remove(prepared, ownership)) {
            ownership.permit().close();
        }
    }

    private CompletionStage<RestoreBackupResult> registerRestoredWorld(
            CompletionStage<RestoreBackupResult> operation,
            RuntimeConfigurationGate.Permit operationPermit) {
        CompletableFuture<RestoreBackupResult> completion = new CompletableFuture<>();
        operation.whenComplete((result, throwable) -> {
            if (throwable != null || result == null) {
                operationPermit.close();
                completion.completeExceptionally(throwable == null
                        ? new IllegalStateException("Restore completed without a result")
                        : throwable);
                return;
            }
            completeRestoredWorldRegistration(
                    result,
                    operationPermit,
                    completion);
        });
        return completion;
    }

    private void completeRestoredWorldRegistration(
            RestoreBackupResult result,
            RuntimeConfigurationGate.Permit operationPermit,
            CompletableFuture<RestoreBackupResult> completion) {
        RuntimeConfigurationGate.Permit registrationPermit = null;
        Throwable failure = null;
        try {
            registrationPermit = runtime.configurationGate()
                    .transitionBackupToConfigurationChange(operationPermit);
            Path restored = result.restoredWorldDirectory().toAbsolutePath().normalize();
            if (!runtime.registerDiscoveredWorldPathHeld(
                    result.restoredWorldId(),
                    restored,
                    runtime.states().currentOrNull())) {
                throw new IllegalStateException(
                        "The restored world identity is registered to another folder");
            }
        } catch (RuntimeException | Error exception) {
            operationPermit.close();
            failure = exception;
        } finally {
            if (registrationPermit != null) {
                registrationPermit.close();
            }
        }
        if (failure == null) {
            completion.complete(result);
        } else {
            completion.completeExceptionally(failure);
        }
    }

    private <T> CompletionStage<T> withHealthyState(
            HealthyStateOperation<T> operation) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            if (state == null || runtime.isClosed()) {
                return failedStage("WorldArchive is still loading");
            }
            Optional<String> storageIssue = runtime.storageIssue(state);
            return storageIssue.isPresent()
                    ? failedStage(storageIssue.orElseThrow())
                    : operation.start(state);
        });
    }

    private static void updateGitAvailability(
            RuntimeState state,
            DestinationHealth health) {
        if (health.status() == DestinationHealthStatus.HEALTHY) {
            state.selector().gitToolsAvailable(true);
        } else if (health.status() == DestinationHealthStatus.TOOL_MISSING) {
            state.selector().gitToolsAvailable(false);
        }
    }

    private static <T> CompletionStage<T> failedStage(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    @FunctionalInterface
    private interface HealthyStateOperation<T> {
        CompletionStage<T> start(RuntimeState state);
    }
}
