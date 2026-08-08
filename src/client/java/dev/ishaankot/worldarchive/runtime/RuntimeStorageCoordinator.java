package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import dev.ishaankot.worldarchive.storage.management.CleanupPlan;
import dev.ishaankot.worldarchive.storage.management.CleanupRequest;
import dev.ishaankot.worldarchive.storage.management.CleanupResult;
import dev.ishaankot.worldarchive.storage.management.StorageOverview;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps storage work and cleanup tokens bound to one immutable runtime state. */
final class RuntimeStorageCoordinator {
    private final WorldArchiveRuntime runtime;

    private final ConcurrentMap<OperationId, OwnedCleanup> owners =
            new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    RuntimeStorageCoordinator(WorldArchiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    CompletionStage<StorageOverview> overview(WorldId worldId) {
        return unavailable()
                ? WorldArchiveRuntime.failedStage("WorldArchive is still loading")
                : runtime.withBackupPermit(() ->
                        runtime.requireCurrentState().storage().overview(worldId));
    }

    CompletionStage<Boolean> claimReviewNotice(WorldId worldId) {
        return unavailable()
                ? CompletableFuture.completedFuture(false)
                : runtime.withBackupPermit(() ->
                        runtime.requireCurrentState().storage().claimReviewNotice(worldId));
    }

    CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        if (unavailable()) {
            return WorldArchiveRuntime.failedStage("WorldArchive is still loading");
        }
        RuntimeConfigurationGate.Permit permit = runtime.configurationGate().enterBackup();
        try {
            var storage = runtime.requireCurrentState().storage();
            CompletionStage<CleanupPlan> prepared = storage.prepareCleanup(worldId);
            return prepared.whenComplete((plan, throwable) -> {
                if (throwable != null || plan == null) {
                    permit.close();
                    return;
                }
                if (closed.get()) {
                    permit.close();
                    throw new IllegalStateException("WorldArchive is shutting down");
                }
                OwnedCleanup owner = new OwnedCleanup(storage, permit);
                OwnedCleanup previous = owners.putIfAbsent(plan.confirmationToken(), owner);
                if (previous != null) {
                    permit.close();
                    throw new IllegalStateException("Cleanup confirmation token already exists");
                }
                if (closed.get()) {
                    release(plan.confirmationToken(), owner);
                    throw new IllegalStateException("WorldArchive is shutting down");
                }
                long delayMillis = Math.max(
                        0,
                        java.time.Duration.between(
                                        runtime.services().clock().instant(),
                                        plan.expiresAt())
                                .toMillis());
                CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                        .execute(() -> release(plan.confirmationToken(), owner));
            });
        } catch (RuntimeException | Error exception) {
            permit.close();
            throw exception;
        }
    }

    CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        OwnedCleanup owner = owners.remove(request.confirmationToken());
        if (owner == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Cleanup preview is missing, expired, or already used"));
        }
        try {
            CompletionStage<CleanupResult> applied = owner.storage().applyCleanup(request);
            applied.whenComplete((ignored, throwable) -> owner.permit().close());
            return applied;
        } catch (RuntimeException | Error exception) {
            owner.permit().close();
            throw exception;
        }
    }

    CompletionStage<Void> discardCleanup(OperationId token) {
        OwnedCleanup owner = owners.remove(Objects.requireNonNull(token, "token"));
        if (owner != null) {
            owner.permit().close();
        }
        return CompletableFuture.completedFuture(null);
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            owners.forEach(this::release);
        }
    }

    CompletionStage<Void> savePolicy(WorldId worldId, StoragePolicy policy) {
        WorldArchiveConfig current = ClientSettingsAccess.snapshot();
        if (current.worlds().stream().noneMatch(world ->
                world.worldId().equals(worldId))) {
            return WorldArchiveRuntime.failedStage(
                    "Storage budgets are available only for configured worlds");
        }
        List<WorldConfig> worlds = current.worlds().stream()
                .map(world -> world.worldId().equals(worldId)
                        ? new WorldConfig(
                                world.worldId(),
                                world.enabled(),
                                world.path(),
                                world.remoteUrl(),
                                world.zipDestination(),
                                policy)
                        : world)
                .toList();
        return ClientSettingsAccess.save(new WorldArchiveConfig(
                        WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                        current.triggers(),
                        current.git(),
                        current.zip(),
                        worlds))
                .thenApply(ignored -> null);
    }

    private boolean unavailable() {
        return closed.get()
                || runtime.isClosed()
                || runtime.states().currentOrNull() == null;
    }

    private void release(OperationId token, OwnedCleanup owner) {
        if (owners.remove(token, owner)) {
            owner.permit().close();
        }
    }

    private record OwnedCleanup(
            dev.ishaankot.worldarchive.storage.management.ManagedStorageService storage,
            RuntimeConfigurationGate.Permit permit) {
        private OwnedCleanup {
            Objects.requireNonNull(storage, "storage");
            Objects.requireNonNull(permit, "permit");
        }
    }
}
