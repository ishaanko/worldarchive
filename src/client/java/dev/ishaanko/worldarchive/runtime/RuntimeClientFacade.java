package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.core.BackupService;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.importing.BackupImportService;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.settings.ClientSettingsAccess;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.CleanupRequest;
import dev.ishaanko.worldarchive.storage.management.CleanupResult;
import dev.ishaanko.worldarchive.storage.management.StorageOverview;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import dev.ishaanko.worldarchive.ui.BackupClientFacade;
import dev.ishaanko.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldEntry;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import dev.ishaanko.worldarchive.ui.model.FolderSelectionResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;

/**
 * The runtime behind the backup screens. Every call returns a stage at once and never throws on
 * the render thread; the disk is read on workers, and nothing here waits for a backup.
 */
final class RuntimeClientFacade implements BackupClientFacade {
    private final ServiceGraph graph;

    private final WorldIdentityResolver resolver;

    private final LiveWorldBackups live;

    private final StateCalls calls;

    private final RuntimeNavigation navigation;

    private final RuntimeBackupService backups;

    private final RuntimeBackupImportService imports;

    private final Path savesDirectory;

    private final Function<WorldId, Optional<String>> settingsWarning;

    RuntimeClientFacade(
            ServiceGraph graph,
            WorldIdentityResolver resolver,
            LiveWorldBackups live,
            StateCalls calls,
            RuntimeNavigation navigation,
            Path savesDirectory,
            Function<WorldId, Optional<String>> settingsWarning) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.live = Objects.requireNonNull(live, "live");
        this.calls = Objects.requireNonNull(calls, "calls");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.savesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory");
        this.settingsWarning = Objects.requireNonNull(settingsWarning, "settingsWarning");
        this.backups = new RuntimeBackupService(calls, resolver);
        this.imports = new RuntimeBackupImportService(calls);
    }

    @Override
    public BackupService backupService() {
        return backups;
    }

    @Override
    public BackupImportService importService() {
        return imports;
    }

    @Override
    public CompletionStage<List<BackupWorldEntry>> backupWorlds() {
        return calls.withState(state -> AsyncTasks.supplyChecked(graph.executor(), () ->
                BackupWorldList.build(state.config(), graph.catalog().listAll(), savesDirectory)));
    }

    @Override
    public CompletionStage<BackupWorldContext> resolveWorld(BackupWorldSelection selection) {
        return calls.withState(state -> AsyncTasks.supplyChecked(graph.executor(), () ->
                switch (resolver.resolve(selection)) {
                    case WorldIdentityResolver.Resolution.Resolved resolved -> resolved.world();
                    case WorldIdentityResolver.Resolution.Refused refused ->
                            throw new IllegalStateException(refused.reason());
                }));
    }

    /**
     * Backs up a world the player picked. The open world is saved by the server first and never
     * copied while it can still change; any other world is copied directly.
     */
    @Override
    public CompletionStage<BackupResult> createManualBackup(
            BackupWorldContext world,
            Optional<String> label,
            ProgressListener progressListener) {
        return calls.withState(state -> {
            if (!resolver.isRegistered(world.worldId(), world.worldDirectory())) {
                return StateCalls.failed(StateCalls.text("screen.worldarchive.runtime.world_unavailable"));
            }
            CreateBackupRequest request;
            switch (TriggerCheck.evaluate(state, resolver.storageIssue(), world, BackupTrigger.MANUAL, label)) {
                case TriggerCheck.Decision.Proceed proceed -> request = proceed.request();
                case TriggerCheck.Decision.Refused refused -> {
                    return StateCalls.failed(refused.message());
                }
            }
            if (busy(world.worldId())) {
                return StateCalls.failed(StateCalls.text("screen.worldarchive.runtime.busy"));
            }
            return switch (live.openWorld(world)) {
                case READY -> live.backUpOpenWorld(state, request, progressListener, graph.gate().enterWork());
                case LOADING -> StateCalls.failed(StateCalls.text("screen.worldarchive.runtime.world_loading"));
                case NOT_OPEN -> backUpClosedWorld(state, request, progressListener);
            };
        });
    }

    /** A world the game does not run cannot change, so it is copied without a save first. */
    private CompletionStage<BackupResult> backUpClosedWorld(
            RuntimeState state,
            CreateBackupRequest request,
            ProgressListener progressListener) {
        ConfigurationGate.Permit permit = graph.gate().enterWork();
        CompletionStage<BackupResult> backup = state.coordinator().createBackup(request, progressListener);
        backup.whenComplete((result, failure) -> state.coordinator().whenIdle(request.worldId())
                .whenComplete((idle, error) -> permit.close()));
        return backup;
    }

    @Override
    public CompletionStage<BackupBrowserCapabilities> browserCapabilities(BackupWorldContext world) {
        // The folder check reads the disk, and the browser asks every second: never on the render thread.
        return calls.withState(state -> AsyncTasks.supply(graph.executor(), () -> capabilities(world, state)));
    }

    private BackupBrowserCapabilities capabilities(BackupWorldContext world, RuntimeState state) {
        Optional<String> storageIssue = resolver.storageIssue();
        boolean sourceAvailable = BackupWorldList.isWorldFolder(world.worldDirectory())
                && resolver.isRegistered(world.worldId(), world.worldDirectory());
        Optional<BackupBrowserCapabilities.CreateBlock> block = Optional.empty();
        if (TriggerCheck.evaluate(state, storageIssue, world, BackupTrigger.MANUAL, Optional.empty())
                instanceof TriggerCheck.Decision.Refused refused) {
            sourceAvailable &= refused.reason() != TriggerCheck.Reason.IDENTITY_ELSEWHERE;
            block = createBlock(refused.reason());
        }
        return new BackupBrowserCapabilities(
                busy(world.worldId()),
                sourceAvailable,
                block,
                state.config().git().enabled() && state.git().remoteConfigured(world.worldId()),
                storageIssue.isEmpty(),
                storageIssue
                        .or(() -> settingsWarning.apply(world.worldId()))
                        .or(() -> live.warning(world.worldId())
                                .map(notice -> RuntimeBackgroundBackupMonitor.text(notice).getString()))
                        .or(() -> state.selector().warning()));
    }

    private static Optional<BackupBrowserCapabilities.CreateBlock> createBlock(TriggerCheck.Reason reason) {
        return switch (reason) {
            case STORAGE_PROBLEM -> Optional.of(BackupBrowserCapabilities.CreateBlock.STORAGE_PROBLEM);
            case WORLD_OFF -> Optional.of(BackupBrowserCapabilities.CreateBlock.WORLD_OFF);
            case TRIGGER_OFF -> Optional.of(BackupBrowserCapabilities.CreateBlock.NO_DESTINATION);
            case GIT_MISSING -> Optional.of(BackupBrowserCapabilities.CreateBlock.GIT_MISSING);
            case IDENTITY_ELSEWHERE -> Optional.empty();
        };
    }

    private boolean busy(WorldId worldId) {
        return graph.busy(worldId) || live.hasPending(worldId);
    }

    @Override
    public CompletionStage<StorageOverview> storageOverview(WorldId worldId) {
        return calls.withState(state -> state.storage().overview(worldId));
    }

    @Override
    public CompletionStage<Boolean> claimStorageReviewNotice(WorldId worldId) {
        if (graph.current().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return calls.withState(state -> state.storage().claimReviewNotice(worldId));
    }

    @Override
    public CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        return calls.withState(state -> state.storage().prepareCleanup(worldId));
    }

    /** Applies a plan of the current state; a plan made before a settings change is refused there. */
    @Override
    public CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        return calls.withPermit(state -> state.storage().applyCleanup(request));
    }

    @Override
    public CompletionStage<Void> saveStoragePolicy(WorldId worldId, StoragePolicy policy) {
        return ClientSettingsAccess.service()
                .update(config -> config.withWorld(worldId, world -> world.withStoragePolicy(policy)))
                .thenApply(ignored -> null);
    }

    @Override
    public CompletionStage<Void> openManagedFolder(BackupWorldContext world, Optional<BackupId> selectedBackup) {
        return navigation.openManagedFolder(world, selectedBackup);
    }

    @Override
    public void openSettings(Screen returnTo) {
        navigation.openSettings(returnTo);
    }

    @Override
    public CompletableFuture<FolderSelectionResult> pickFolder(String title) {
        return ClientSettingsAccess.pickFolder(title, Optional.empty());
    }

    @Override
    public Optional<GameVersionStamp> runningGameVersion() {
        return RunningGameVersion.current();
    }

    @Override
    public void selectRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        navigation.selectRestoredWorld(returnTo, result);
    }

    @Override
    public void playRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        navigation.playRestoredWorld(returnTo, result);
    }

    /** Opens the open world's backup browser over {@code returnTo}; false while its identity is unknown. */
    boolean openLiveBrowser(Screen returnTo) {
        Optional<BackupWorldContext> world = live.liveWorld();
        if (world.isEmpty() || graph.current().isEmpty()) {
            return false;
        }
        navigation.openBrowser(returnTo, world.get(), this);
        return true;
    }
}
