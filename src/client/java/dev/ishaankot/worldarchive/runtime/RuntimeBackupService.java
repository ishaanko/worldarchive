package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.core.BackupCoordinator;
import dev.ishaankot.worldarchive.core.BackupService;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Stable maintenance-service facade over atomically replaceable runtime service graphs. */
final class RuntimeBackupService implements BackupService {
    private final WorldArchiveRuntime runtime;

    private final BackupCoordinator coordinator;

    RuntimeBackupService(WorldArchiveRuntime runtime, BackupCoordinator coordinator) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public CompletionStage<BackupResult> createBackup(
            CreateBackupRequest request,
            ProgressListener progressListener) {
        return coordinator.createBackup(request, progressListener);
    }

    @Override
    public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            return state == null || runtime.isClosed()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().maintenanceService().listBackups(worldId);
        });
    }

    @Override
    public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
        return runtime.withBackupPermit(() -> {
            RuntimeState state = runtime.states().currentOrNull();
            return state == null || runtime.isClosed()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().maintenanceService().findBackup(backupId);
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
            operation = state.coordinator().maintenanceService()
                    .restoreBackup(request, progressListener);
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
                    : state.coordinator().maintenanceService().prepareDelete(backupId);
        });
    }

    @Override
    public CompletionStage<BackupResult> deleteBackup(
            DeleteBackupRequest request,
            ProgressListener progressListener) {
        return withHealthyState(state -> state.coordinator()
                .maintenanceService()
                .deleteBackup(request, progressListener));
    }

    @Override
    public CompletionStage<BackupResult> verifyBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return withHealthyState(state -> state.coordinator()
                .maintenanceService()
                .verifyBackup(backupId, progressListener));
    }

    @Override
    public CompletionStage<BackupResult> syncBackup(
            BackupId backupId,
            ProgressListener progressListener) {
        return withHealthyState(state -> state.coordinator()
                .maintenanceService()
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
            return state.coordinator().maintenanceService().health(worldId).thenApply(health -> {
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
