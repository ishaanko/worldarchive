package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
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

/** Stable storage facade over atomically replaceable runtime service graphs. */
final class RuntimeStorageCoordinator {
    private final WorldArchiveRuntime runtime;

    RuntimeStorageCoordinator(WorldArchiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    CompletionStage<StorageOverview> overview(WorldId worldId) {
        RuntimeState state = state();
        return state == null
                ? WorldArchiveRuntime.failedStage("WorldArchive is still loading")
                : state.storage().overview(worldId);
    }

    CompletionStage<Boolean> claimReviewNotice(WorldId worldId) {
        RuntimeState state = state();
        return state == null
                ? CompletableFuture.completedFuture(false)
                : state.storage().claimReviewNotice(worldId);
    }

    CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        RuntimeState state = state();
        return state == null
                ? WorldArchiveRuntime.failedStage("WorldArchive is still loading")
                : state.storage().prepareCleanup(worldId);
    }

    CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        RuntimeState state = state();
        return state == null
                ? WorldArchiveRuntime.failedStage("WorldArchive is still loading")
                : state.storage().applyCleanup(request);
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

    private RuntimeState state() {
        return runtime.isClosed() ? null : runtime.states().currentOrNull();
    }
}
