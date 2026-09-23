package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.ConfirmationLedger;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Measures each world's managed storage, forecasts its growth, previews cleanup, and applies the
 * cleanup the player confirms. Nothing is deleted without a confirmed preview.
 */
public final class ManagedStorageService {
    private final Executor executor;

    private final Clock clock;

    private final WorldGitSnapshotStore git;

    private final StorageOverviewBuilder overviewBuilder;

    private final CleanupPlanner cleanupPlanner;

    private final CleanupExecutor cleanupExecutor;

    private final ConfirmationLedger<WorldId, CleanupPlan> confirmations =
            new ConfirmationLedger<>(CleanupPlan::expiresAt);

    public ManagedStorageService(
            Supplier<WorldArchiveConfig> config,
            BackupCatalog catalog,
            FileBackupDeletionRegistry deletions,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            FileStorageHistoryStore history,
            WorldOperationGate operationGate,
            Executor executor,
            Clock clock,
            ZoneId zoneId) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.git = Objects.requireNonNull(git, "git");
        this.overviewBuilder = new StorageOverviewBuilder(config, catalog, git, zipStores, history, clock, zoneId);
        this.cleanupExecutor = new CleanupExecutor(catalog, deletions, git, operationGate, overviewBuilder);
        this.cleanupPlanner = new CleanupPlanner(clock, zoneId);
    }

    /** Measures the world now; with a budget, the measurement becomes today's forecast sample. */
    public CompletionStage<StorageOverview> overview(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supplyChecked(executor, () -> {
            Snapshot snapshot = overviewBuilder.snapshot(worldId);
            overviewBuilder.recordSample(worldId, snapshot.totalBytes());
            return overviewBuilder.build(snapshot);
        });
    }

    public CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supplyChecked(executor, () -> {
            Snapshot snapshot = overviewBuilder.snapshot(worldId);
            if (!snapshot.world().storagePolicy().budgetEnabled()) {
                throw new IOException("Configure a storage budget before reviewing cleanup");
            }
            CleanupPlan plan = cleanupPlanner.prepare(
                    worldId, snapshot, () -> AsyncTasks.await(git.remoteSnapshotCommits(worldId)));
            confirmations.expireStaleEntries(clock.instant());
            confirmations.put(worldId, plan);
            return plan;
        });
    }

    /**
     * Whether the Worlds screen should suggest a storage review for the world: its budget is on
     * and cleanup is recommended. A world without a budget costs no disk access at all.
     */
    public CompletionStage<Boolean> claimReviewNotice(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        boolean budgetEnabled = overviewBuilder.world(worldId)
                .filter(world -> world.storagePolicy().budgetEnabled())
                .isPresent();
        if (!budgetEnabled) {
            return CompletableFuture.completedFuture(false);
        }
        return AsyncTasks.supplyChecked(executor, () ->
                overviewBuilder.build(overviewBuilder.snapshot(worldId)).cleanupReviewRecommended());
    }

    public CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        Objects.requireNonNull(request, "request");
        return AsyncTasks.supplyChecked(executor, () -> {
            CleanupPlan plan = confirmations.claimMatching(
                            clock.instant(), candidate -> candidate.confirmationToken().equals(request.confirmationToken()))
                    .orElseThrow(() -> new IOException("Cleanup confirmation is invalid, expired, or already used"));
            return cleanupExecutor.apply(plan, request);
        });
    }
}
