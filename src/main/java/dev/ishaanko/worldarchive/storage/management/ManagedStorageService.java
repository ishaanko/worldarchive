package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.AsyncTasks;
import dev.ishaanko.worldarchive.core.ConfirmationLedger;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Measures, forecasts, previews, and explicitly applies managed-local cleanup. */
public final class ManagedStorageService {
    private final FileStorageReviewStore reviews;

    private final Executor executor;

    private final Clock clock;

    private final StorageOverviewBuilder overviewBuilder;

    private final CleanupPlanner cleanupPlanner;

    private final CleanupExecutor cleanupExecutor;

    private final ConfirmationLedger<WorldId, CleanupPlan> confirmations =
            new ConfirmationLedger<>(CleanupPlan::expiresAt);

    public ManagedStorageService(
            Supplier<WorldArchiveConfig> config,
            BackupCatalog catalog,
            BackupDeletionRegistry deletions,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            FileStorageHistoryStore history,
            FileStorageReviewStore reviews,
            WorldOperationGate operationGate,
            Executor executor,
            Clock clock,
            ZoneId zoneId) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(deletions, "deletions");
        Objects.requireNonNull(git, "git");
        Objects.requireNonNull(zipStores, "zipStores");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(operationGate, "operationGate");
        Objects.requireNonNull(zoneId, "zoneId");
        this.overviewBuilder = new StorageOverviewBuilder(
                config, catalog, git, zipStores, history, clock);
        this.cleanupExecutor = new CleanupExecutor(
                catalog, deletions, git, operationGate, overviewBuilder);
        this.cleanupPlanner = new CleanupPlanner(cleanupExecutor, clock, zoneId);
    }

    public CompletionStage<StorageOverview> overview(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supply(executor, () -> {
            try {
                Snapshot snapshot = overviewBuilder.snapshot(worldId);
                return overviewBuilder.build(snapshot, true);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supply(executor, () -> {
            try {
                return prepareCleanupBlocking(worldId);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletionStage<Boolean> claimReviewNotice(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supply(executor, () -> {
            try {
                StorageOverview current = overviewBuilder.build(
                        overviewBuilder.snapshot(worldId), true);
                return current.cleanupReviewRecommended()
                        && reviews.claimIfDue(worldId, clock.instant());
            } catch (Exception exception) {
                return false;
            }
        });
    }

    public CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        Objects.requireNonNull(request, "request");
        return AsyncTasks.supply(executor, () -> {
            try {
                return applyCleanupBlocking(request);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private CleanupPlan prepareCleanupBlocking(WorldId worldId) throws Exception {
        Snapshot snapshot = overviewBuilder.snapshot(worldId);
        if (!snapshot.world().storagePolicy().budgetEnabled()) {
            throw new IOException("Configure a storage budget before reviewing cleanup");
        }
        CleanupPlan plan = cleanupPlanner.prepare(worldId, snapshot);
        confirmations.expireStaleEntries(clock.instant());
        confirmations.put(worldId, plan);
        return plan;
    }

    private CleanupResult applyCleanupBlocking(CleanupRequest request) throws Exception {
        CleanupPlan plan = claimConfirmation(request.confirmationToken());
        if (plan == null) {
            throw new IOException("Cleanup confirmation is invalid, expired, or already used");
        }
        return cleanupExecutor.apply(plan, request);
    }

    private CleanupPlan claimConfirmation(OperationId token) {
        return confirmations.claimMatching(
                clock.instant(),
                plan -> plan.confirmationToken().equals(token))
                .orElse(null);
    }
}
