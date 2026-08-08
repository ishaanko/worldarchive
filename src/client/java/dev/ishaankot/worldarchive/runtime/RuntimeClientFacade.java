package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.core.BackupService;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.importing.BackupImportService;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.management.CleanupPlan;
import dev.ishaankot.worldarchive.storage.management.CleanupRequest;
import dev.ishaankot.worldarchive.storage.management.CleanupResult;
import dev.ishaankot.worldarchive.storage.management.StorageOverview;
import dev.ishaankot.worldarchive.ui.BackupClientFacade;
import dev.ishaankot.worldarchive.ui.BackupWorldContext;
import dev.ishaankot.worldarchive.ui.BackupWorldEntry;
import dev.ishaankot.worldarchive.ui.BackupWorldSelection;
import dev.ishaankot.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;

/** Implements the native-screen facade against one runtime's service graph. */
final class RuntimeClientFacade implements BackupClientFacade {
    private final WorldArchiveRuntime runtime;

    private final BackupService serviceView;

    private final BackupImportService importsView;

    private final RuntimeStorageCoordinator storageView;

    private final RuntimeNavigation navigation;

    private final RuntimeLifecycle lifecycle;

    private final RuntimeBackgroundBackupMonitor backgroundBackups;

    RuntimeClientFacade(
            WorldArchiveRuntime runtime,
            BackupService serviceView,
            BackupImportService importsView,
            RuntimeStorageCoordinator storageView,
            RuntimeNavigation navigation,
            RuntimeLifecycle lifecycle,
            RuntimeBackgroundBackupMonitor backgroundBackups) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.serviceView = Objects.requireNonNull(serviceView, "serviceView");
        this.importsView = Objects.requireNonNull(importsView, "importsView");
        this.storageView = Objects.requireNonNull(storageView, "storageView");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.backgroundBackups = Objects.requireNonNull(backgroundBackups, "backgroundBackups");
    }

    @Override
    public BackupService backupService() {
        return serviceView;
    }

    @Override
    public BackupImportService importService() {
        return importsView;
    }

    @Override
    public CompletionStage<List<BackupWorldEntry>> backupWorlds() {
        if (runtime.unavailable()) {
            return WorldArchiveRuntime.failedStage("WorldArchive is still loading");
        }
        return runtime.submit(
                () -> new RuntimeBackupWorlds(runtime, runtime.services().catalog()).list());
    }

    @Override
    public CompletionStage<Optional<BackupWorldContext>> resolveWorld(
            BackupWorldSelection selection) {
        Objects.requireNonNull(selection, "selection");
        if (runtime.unavailable()) {
            return WorldArchiveRuntime.failedStage("WorldArchive is still loading");
        }
        return runtime.submit(() -> runtime.resolveWorldBlocking(selection));
    }

    @Override
    public CompletionStage<BackupResult> createManualBackup(
            BackupWorldContext world,
            Optional<String> label,
            ProgressListener progressListener) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(progressListener, "progressListener");
        return runtime.withBackupPermit(() -> {
            if (!runtime.actionContexts().sourceActionsAllowed(world)) {
                return WorldArchiveRuntime.failedStage(
                        "The original world is unavailable for backup creation");
            }
            RuntimeState state = runtime.states().currentOrNull();
            if (state == null || runtime.isClosed()) {
                return WorldArchiveRuntime.failedStage("WorldArchive is still loading");
            }
            Optional<String> storageIssue = runtime.storageIssue(state);
            if (storageIssue.isPresent()) {
                return WorldArchiveRuntime.failedStage(storageIssue.orElseThrow());
            }
            CreateBackupRequest request = WorldArchiveRuntime.request(
                    world, label, BackupTrigger.MANUAL);
            if (!runtime.registerWorldPath(world.worldId(), world.worldDirectory(), state)) {
                return WorldArchiveRuntime.failedStage(
                        "The world identity is already registered to a different folder");
            }
            if (!state.enabledDestinations(request)) {
                return WorldArchiveRuntime.failedStage("Manual backups are disabled for this world");
            }
            if (runtime.busyAcrossStates(world.worldId())) {
                return WorldArchiveRuntime.failedStage("A backup is already running for this world");
            }
            IntegratedServer server = lifecycle.matchingServer(world);
            if (server != null) {
                return lifecycle.queueRequestedSave(
                        state,
                        server,
                        request,
                        progressListener);
            }
            return state.coordinator().createBackup(request, progressListener);
        });
    }

    @Override
    public CompletionStage<BackupBrowserCapabilities> browserCapabilities(
            BackupWorldContext world) {
        Objects.requireNonNull(world, "world");
        RuntimeState state = runtime.states().currentOrNull();
        if (state == null || runtime.isClosed()) {
            return WorldArchiveRuntime.failedStage("WorldArchive is still loading");
        }
        CreateBackupRequest request = WorldArchiveRuntime.request(
                world, Optional.empty(), BackupTrigger.MANUAL);
        WorldArchiveConfig config = state.config();
        boolean sourceAvailable = navigation.sourceDirectoryAvailable(world);
        boolean createAvailable = false;
        if (sourceAvailable) {
            try {
                createAvailable = state.enabledDestinations(request);
            } catch (IllegalArgumentException exception) {
                sourceAvailable = false;
            }
        }
        boolean folderAvailable = config.zip().destination().isPresent()
                || config.git().repository().isPresent();
        Optional<String> storageIssue = runtime.storageIssue(state);
        return CompletableFuture.completedFuture(new BackupBrowserCapabilities(
                runtime.busyAcrossStates(world.worldId()),
                storageIssue.isEmpty() && sourceAvailable && createAvailable,
                config.git().enabled() && state.gitBackend().remoteConfigured(world.worldId()),
                storageIssue.isEmpty() && folderAvailable,
                storageIssue.or(() -> runtime.worldSettingsWarning()
                        .or(() -> backgroundBackups.warning()
                                .or(state.selector()::warning)))));
    }

    @Override
    public CompletionStage<StorageOverview> storageOverview(WorldId worldId) {
        return storageView.overview(Objects.requireNonNull(worldId, "worldId"));
    }

    @Override
    public CompletionStage<Boolean> claimStorageReviewNotice(WorldId worldId) {
        return storageView.claimReviewNotice(Objects.requireNonNull(worldId, "worldId"));
    }

    @Override
    public CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        return storageView.prepareCleanup(Objects.requireNonNull(worldId, "worldId"));
    }

    @Override
    public CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        return storageView.applyCleanup(Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<Void> discardCleanup(OperationId confirmationToken) {
        return storageView.discardCleanup(
                Objects.requireNonNull(confirmationToken, "confirmationToken"));
    }

    @Override
    public CompletionStage<Void> saveStoragePolicy(
            WorldId worldId,
            StoragePolicy policy) {
        return storageView.savePolicy(
                Objects.requireNonNull(worldId, "worldId"),
                Objects.requireNonNull(policy, "policy"));
    }

    @Override
    public void openManagedFolder(
            BackupWorldContext world,
            Optional<BackupRow> selectedBackup) {
        navigation.openManagedFolder(world, selectedBackup);
    }

    @Override
    public void openSettings(Screen returnTo) {
        navigation.openSettings(returnTo);
    }

    @Override
    public void selectRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        navigation.selectRestoredWorld(returnTo, result);
    }

    @Override
    public void playRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        navigation.playRestoredWorld(returnTo, result);
    }
}
