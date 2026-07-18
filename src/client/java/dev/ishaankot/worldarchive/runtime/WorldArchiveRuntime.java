package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.WorldArchiveMetadata;
import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.catalog.FileBackupCatalog;
import dev.ishaankot.worldarchive.command.BackupCommandFacade;
import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.BackupBackend;
import dev.ishaankot.worldarchive.core.BackupCaptureGate;
import dev.ishaankot.worldarchive.core.BackupCoordinator;
import dev.ishaankot.worldarchive.core.BackupService;
import dev.ishaankot.worldarchive.core.CaptureProgressListener;
import dev.ishaankot.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
import dev.ishaankot.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaankot.worldarchive.core.FileWorldInventoryStore;
import dev.ishaankot.worldarchive.core.LockingWorldOperationGate;
import dev.ishaankot.worldarchive.core.NoCatchUpSchedule;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.PreparedBackup;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupRequest;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.recovery.BackupRecoveryService;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import dev.ishaankot.worldarchive.settings.SettingsDefaults;
import dev.ishaankot.worldarchive.settings.WorldFolderDiscovery;
import dev.ishaankot.worldarchive.storage.git.GitBackendSettings;
import dev.ishaankot.worldarchive.storage.git.GitBackupBackend;
import dev.ishaankot.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupBackend;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaankot.worldarchive.ui.BackupBrowserScreen;
import dev.ishaankot.worldarchive.ui.BackupClientFacade;
import dev.ishaankot.worldarchive.ui.BackupWorldContext;
import dev.ishaankot.worldarchive.ui.BackupWorldSelection;
import dev.ishaankot.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-owned service graph, lifecycle save gate, and native-screen facade. */
public final class WorldArchiveRuntime implements BackupCommandFacade, BackupClientFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldArchiveMetadata.MOD_NAME);

    private static final Duration CLIENT_SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private static final AtomicReference<WorldArchiveRuntime> INSTANCE = new AtomicReference<>();

    private final Minecraft minecraft;

    private final Path storageRoot;

    private final BackupCatalog catalog;

    private final RuntimeNoticeStore noticeStore;

    private final FileWorldInventoryStore inventoryStore;

    private final FileSystemBackupCaptureFactory captureFactory;

    private final WorldIdentityStore identityStore = new WorldIdentityStore();

    private final LockingWorldOperationGate captureMutex = new LockingWorldOperationGate();

    private final LockingWorldOperationGate operationGate = new LockingWorldOperationGate();

    private final ExecutorService workerExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("worldarchive-worker-", 0).factory());

    private final Clock clock = Clock.systemUTC();

    private final RuntimeStateRegistry<RuntimeState> stateRegistry = new RuntimeStateRegistry<>();

    private final ConcurrentMap<PreparedBackup, PreparedOwnership> externallyPrepared =
            new ConcurrentHashMap<>();

    private final RuntimeWorldPathRegistry worldPaths = new RuntimeWorldPathRegistry();

    private final RuntimeStorageSafety storageSafety = new RuntimeStorageSafety();

    private final RuntimeConfigurationGate configurationGate = new RuntimeConfigurationGate();

    private final RuntimeActionContextRegistry actionContexts =
            new RuntimeActionContextRegistry();

    private final Set<CompletableFuture<BackupResult>> exitWork = ConcurrentHashMap.newKeySet();

    private final RuntimeCoordinator coordinatorView = new RuntimeCoordinator();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicReference<Optional<String>> backgroundWarning =
            new AtomicReference<>(Optional.empty());

    private final Set<WorldId> worldSettingsFailures = ConcurrentHashMap.newKeySet();

    private final AtomicReference<Optional<String>> retainedStartupWarning =
            new AtomicReference<>(Optional.empty());

    private final AtomicBoolean retainedWarningShown = new AtomicBoolean();

    private final Object stateLock = new Object();

    private final Object lifecycleLock = new Object();

    private IntegratedServer activeServer;

    private BackupWorldContext liveWorld;

    private long liveWorldRevision;

    private IntegratedServer stoppingServer;

    private boolean clientStopping;

    private final LiveBackupSaveGate<PendingLiveBackup> liveSaveGate =
            new LiveBackupSaveGate<>();

    private ScheduleState scheduleState;

    private WorldArchiveRuntime(Minecraft minecraft) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.storageRoot = minecraft.gameDirectory.toPath()
                .toAbsolutePath()
                .normalize()
                .resolve("worldarchive");
        this.noticeStore = new RuntimeNoticeStore(
                storageRoot.resolve("last-background-warning.txt"));
        try {
            retainedStartupWarning.set(noticeStore.load());
        } catch (IOException exception) {
            LOGGER.warn("Stored background backup notice could not be loaded");
        }
        this.catalog = new FileBackupCatalog(storageRoot.resolve("catalog.json"));
        this.inventoryStore = new FileWorldInventoryStore(storageRoot.resolve("inventories"));
        this.captureFactory = new FileSystemBackupCaptureFactory(storageRoot.resolve("capture-temp"));
    }

    /** Starts the singleton and returns immediately; settings finish loading asynchronously. */
    public static synchronized WorldArchiveRuntime initialize() {
        WorldArchiveRuntime existing = INSTANCE.get();
        if (existing != null) {
            return existing;
        }
        WorldArchiveRuntime created = new WorldArchiveRuntime(Minecraft.getInstance());
        INSTANCE.set(created);
        created.registerLifecycleEvents();
        ClientSettingsAccess.addConfigurationGuard(created::acquireConfigurationChange);
        ClientSettingsAccess.addConfigurationListener(created::reload);
        ClientSettingsAccess.ready().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                created.logFailure("Runtime settings could not be loaded", throwable);
                return;
            }
            try {
                created.reload(ClientSettingsAccess.snapshot());
            } catch (RuntimeException exception) {
                created.logFailure("Runtime services could not be configured", exception);
            }
        });
        return created;
    }

    /** Returns the singleton, creating its non-blocking initialization when necessary. */
    public static WorldArchiveRuntime instance() {
        return initialize();
    }

    /** Atomically installs a service graph whose paths and selector share one config snapshot. */
    public void reload(WorldArchiveConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (stateLock) {
            if (closed.get()) {
                throw new IllegalStateException("WorldArchive runtime is shut down");
            }
            WorldArchiveConfig resolved = new SettingsDefaults(storageRoot).resolve(config);
            RuntimeState current = stateRegistry.currentOrNull();
            if (current != null && current.config().equals(resolved)) {
                clearPersistedWorldSettingsFailures(resolved);
                return;
            }
            RuntimeState replacement = buildState(resolved);
            stateRegistry.install(replacement);
            reconcileWorldPathsAndSchedule(replacement);
            refreshStorageSafety(replacement);
            clearPersistedWorldSettingsFailures(resolved);
        }
        ensureLiveWorldResolution();
    }

    private void validateConfigurationChange(WorldArchiveConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (stateLock) {
            RuntimeState current = stateRegistry.currentOrNull();
            if (closed.get() || current == null) {
                return;
            }
            RuntimeStoragePaths replacement = RuntimeStoragePaths.from(
                    new SettingsDefaults(storageRoot).resolve(config),
                    storageRoot);
            if (current.storagePaths().equals(replacement)) {
                return;
            }
            List<DestinationResult> destinations;
            try {
                destinations = catalog.listAll().stream()
                        .flatMap(record -> record.result().destinations().stream())
                        .toList();
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Destination paths cannot be validated against the backup catalog",
                        exception);
            }
            RuntimeDestinationPathGuard.requireAllowed(
                    current.storagePaths(),
                    replacement,
                    destinations);
        }
    }

    private Runnable acquireConfigurationChange(WorldArchiveConfig config) {
        WorldArchiveConfig resolved = new SettingsDefaults(storageRoot).resolve(
                Objects.requireNonNull(config, "config"));
        try {
            resolved.validateDestinations(worldPaths.snapshotPaths());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    safeMessage(exception, "A destination overlaps a known world folder"),
                    exception);
        }
        synchronized (stateLock) {
            RuntimeState current = stateRegistry.currentOrNull();
            if (closed.get() || current == null || current.storagePaths().equals(
                    RuntimeStoragePaths.from(resolved, storageRoot))) {
                return () -> { };
            }
        }
        RuntimeConfigurationGate.Permit permit = configurationGate.tryEnterConfigurationChange();
        try {
            validateConfigurationChange(resolved);
            return permit::close;
        } catch (RuntimeException | Error exception) {
            permit.close();
            throw exception;
        }
    }

    /** Waits only for exit-triggered work and never waits beyond the supplied duration. */
    public boolean awaitExitWork(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        boolean interrupted = false;
        try {
            while (!exitWork.isEmpty()) {
                CompletableFuture<?>[] work = exitWork.toArray(CompletableFuture[]::new);
                if (work.length == 0) {
                    return true;
                }
                try {
                    CompletableFuture.allOf(work).get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (ExecutionException | CancellationException exception) {
                    // A completed failure still counts as settled exit work.
                } catch (TimeoutException exception) {
                    return false;
                } catch (InterruptedException exception) {
                    interrupted = true;
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0 && !exitWork.isEmpty()) {
                    return false;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Performs bounded exit draining, rejects new work, and releases runtime-owned workers. */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        PendingLiveBackup abandoned;
        synchronized (lifecycleLock) {
            abandoned = liveSaveGate.clear().orElse(null);
            scheduleState = null;
            liveWorld = null;
            activeServer = null;
            stoppingServer = null;
            clientStopping = false;
            liveWorldRevision++;
        }
        actionContexts.clear();
        if (abandoned != null) {
            abandoned.fail(
                    new IllegalStateException("Minecraft stopped before the pending save completed"));
        }
        if (!awaitExitWork(CLIENT_SHUTDOWN_WAIT)) {
            observeExitResult(
                    null,
                    new IllegalStateException("World-exit backup did not settle before shutdown"));
            LOGGER.warn("WorldArchive exit work exceeded the bounded shutdown wait");
        }
        synchronized (stateLock) {
            // Wait for an already-started immutable state swap before closing every state.
        }
        externallyPrepared.forEach((prepared, ownership) -> {
            if (externallyPrepared.remove(prepared, ownership)) {
                try {
                    prepared.close();
                } catch (IOException exception) {
                    logFailure("A prepared capture could not be released", exception);
                } finally {
                    ownership.permit().close();
                }
            }
        });
        stateRegistry.retained().forEach(RuntimeState::close);
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workerExecutor.shutdownNow();
        }
        ClientSettingsAccess.shutdown();
    }

    @Override
    public BackupCoordinator backups() {
        return coordinatorView;
    }

    @Override
    public BackupService backupService() {
        return coordinatorView;
    }

    @Override
    public Optional<WorldId> activeWorldId() {
        if (closed.get() || stateRegistry.currentOrNull() == null) {
            return Optional.empty();
        }
        synchronized (lifecycleLock) {
            return Optional.ofNullable(liveWorld).map(BackupWorldContext::worldId);
        }
    }

    @Override
    public CompletionStage<BackupResult> createManualBackup(
            Optional<String> label,
            ProgressListener progressListener) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(progressListener, "progressListener");
        BackupWorldContext world;
        synchronized (lifecycleLock) {
            world = liveWorld;
        }
        if (world == null) {
            return failedStage("No integrated world is ready for backup");
        }
        return createManualBackup(world, label, progressListener);
    }

    @Override
    public CompletionStage<Optional<BackupWorldContext>> resolveWorld(
            BackupWorldSelection selection) {
        Objects.requireNonNull(selection, "selection");
        if (unavailable()) {
            return failedStage("WorldArchive is still loading");
        }
        return submit(() -> resolveWorldBlocking(selection));
    }

    @Override
    public CompletionStage<BackupResult> createManualBackup(
            BackupWorldContext world,
            Optional<String> label,
            ProgressListener progressListener) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(progressListener, "progressListener");
        return withBackupPermit(() -> {
            if (!actionContexts.sourceActionsAllowed(world)) {
                return failedStage("The original world is unavailable for backup creation");
            }
            RuntimeState state = stateRegistry.currentOrNull();
            if (state == null || closed.get()) {
                return failedStage("WorldArchive is still loading");
            }
            Optional<String> storageIssue = storageIssue(state);
            if (storageIssue.isPresent()) {
                return failedStage(storageIssue.orElseThrow());
            }
            CreateBackupRequest request = request(world, label, BackupTrigger.MANUAL);
            if (!registerWorldPath(world.worldId(), world.worldDirectory(), state)) {
                return failedStage("The world identity is already registered to a different folder");
            }
            if (!state.enabledDestinations(request)) {
                return failedStage("Manual backups are disabled for this world");
            }
            if (busyAcrossStates(world.worldId())) {
                return failedStage("A backup is already running for this world");
            }
            LiveMatch match = liveMatch(world);
            if (match != null) {
                return queueRequestedSave(state, match.server(), request, progressListener);
            }
            return state.coordinator().createBackup(request, progressListener);
        });
    }

    @Override
    public CompletionStage<BackupBrowserCapabilities> browserCapabilities(
            BackupWorldContext world) {
        Objects.requireNonNull(world, "world");
        RuntimeState state = stateRegistry.currentOrNull();
        if (state == null || closed.get()) {
            return failedStage("WorldArchive is still loading");
        }
        CreateBackupRequest request = request(world, Optional.empty(), BackupTrigger.MANUAL);
        WorldArchiveConfig config = state.config();
        boolean sourceAvailable = sourceDirectoryAvailable(world);
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
        Optional<String> storageIssue = storageIssue(state);
        return CompletableFuture.completedFuture(new BackupBrowserCapabilities(
                busyAcrossStates(world.worldId()),
                storageIssue.isEmpty() && sourceAvailable && createAvailable,
                config.git().enabled() && config.git().remoteUrl().isPresent(),
                storageIssue.isEmpty() && folderAvailable,
                storageIssue.or(() -> worldSettingsWarning()
                        .or(() -> backgroundWarning.get().or(state.selector()::warning)))));
    }

    @Override
    public void openManagedFolder(
            BackupWorldContext world,
            Optional<BackupRow> selectedBackup) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(selectedBackup, "selectedBackup");
        RuntimeConfigurationGate.Permit permit = configurationGate.enterBackup();
        boolean transferred = false;
        try {
            RuntimeState state = requireCurrentState();
            storageIssue(state).ifPresent(issue -> {
                throw new IllegalStateException(issue);
            });
            Path destination = managedPath(state, world, selectedBackup);
            workerExecutor.execute(() -> {
                boolean clientCallbackScheduled = false;
                try {
                    Files.createDirectories(destination);
                    if (!closed.get()) {
                        minecraft.execute(() -> {
                            try {
                                if (!closed.get()) {
                                    Util.getPlatform().openPath(destination);
                                }
                            } catch (RuntimeException exception) {
                                logFailure("Managed backup folder could not be opened", exception);
                            } finally {
                                permit.close();
                            }
                        });
                        clientCallbackScheduled = true;
                    }
                } catch (IOException | RuntimeException exception) {
                    logFailure("Managed backup folder could not be opened", exception);
                } finally {
                    if (!clientCallbackScheduled) {
                        permit.close();
                    }
                }
            });
            transferred = true;
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("WorldArchive is shutting down");
        } finally {
            if (!transferred) {
                permit.close();
            }
        }
    }

    @Override
    public void openSettings(Screen returnTo) {
        Objects.requireNonNull(returnTo, "returnTo");
        if (!closed.get()) {
            minecraft.execute(() -> minecraft.setScreenAndShow(ClientSettingsAccess.createScreen(returnTo)));
        }
    }

    @Override
    public void selectRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        Objects.requireNonNull(returnTo, "returnTo");
        Objects.requireNonNull(result, "result");
        minecraft.execute(() -> {
            if (closed.get()) {
                return;
            }
            Optional<String> storageName = validatedRestoredStorageName(result);
            if (storageName.isEmpty()) {
                return;
            }
            SelectWorldScreen screen = new SelectWorldScreen(returnTo);
            minecraft.setScreenAndShow(screen);
            RestoredWorldSelection.install(screen, storageName.orElseThrow());
        });
    }

    @Override
    public void playRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        Objects.requireNonNull(returnTo, "returnTo");
        Objects.requireNonNull(result, "result");
        minecraft.execute(() -> {
            if (closed.get()) {
                return;
            }
            Optional<String> storageName = validatedRestoredStorageName(result);
            if (storageName.isEmpty()) {
                return;
            }
            String name = storageName.orElseThrow();
            try (LevelStorageSource.LevelStorageAccess ignored =
                    minecraft.getLevelSource().validateAndCreateAccess(name)) {
                // Validation and a clean session close happen before vanilla starts the world.
            } catch (IOException | ContentValidationException exception) {
                logFailure("Restored world validation failed", exception);
                minecraft.setScreenAndShow(returnTo);
                return;
            }
            minecraft.createWorldOpenFlows().openWorld(
                    name,
                    () -> minecraft.setScreenAndShow(returnTo));
        });
    }

    @Override
    public void openBrowser() {
        BackupWorldContext world;
        synchronized (lifecycleLock) {
            world = liveWorld;
        }
        if (world == null || unavailable()) {
            return;
        }
        BackupWorldContext selected = world;
        minecraft.execute(() -> {
            if (!closed.get()) {
                minecraft.setScreenAndShow(new BackupBrowserScreen(
                        new PauseScreen(true),
                        selected,
                        this));
            }
        });
    }

    @Override
    public void openRestore(BackupId backupId) {
        openBrowserForBackup(backupId, CommandAction.RESTORE);
    }

    @Override
    public void openDeleteConfirmation(BackupId backupId) {
        openBrowserForBackup(backupId, CommandAction.DELETE);
    }

    @Override
    public void openSettings() {
        openSettings(new PauseScreen(true));
    }

    private RuntimeState buildState(WorldArchiveConfig config) {
        RuntimeStoragePaths storagePaths = RuntimeStoragePaths.from(config, storageRoot);
        Path gitRepository = storagePaths.gitRepository();
        Path zipDirectory = storagePaths.zipDirectory();
        GitBackupBackend gitBackend = new GitBackupBackend(
                GitBackendSettings.from(config.git(), gitRepository),
                new SystemGitCommandRunner(),
                workerExecutor);
        ZipBackupStore zipStore = new ZipBackupStore(zipDirectory);
        ZipBackupBackend zipBackend = new ZipBackupBackend(zipStore, workerExecutor);
        List<BackupBackend> backends = List.of(gitBackend, zipBackend);
        ConfiguredBackupDestinationSelector selector =
                new ConfiguredBackupDestinationSelector(() -> config, backends);
        RuntimeDestinationSelector runtimeSelector = new RuntimeDestinationSelector(selector);
        BackupRecoveryService recovery = new BackupRecoveryService(
                catalog,
                Optional.of(gitBackend),
                Optional.of(zipStore),
                identityStore,
                new MinecraftRestoredWorldMetadataFinalizer(),
                workerExecutor,
                operationGate);
        SerializedBackupCoordinator coordinator = new SerializedBackupCoordinator(
                catalog,
                captureFactory,
                inventoryStore,
                runtimeSelector,
                recovery,
                BackupCaptureGate.DIRECT,
                captureMutex,
                operationGate,
                workerExecutor,
                clock);
        RuntimeState state = new RuntimeState(
                config,
                storagePaths,
                gitBackend,
                runtimeSelector,
                coordinator);
        if (config.git().enabled()) {
            gitBackend.probeTools().whenComplete((health, throwable) -> {
                if (throwable != null || health == null) {
                    runtimeSelector.gitToolProbeFailed();
                } else {
                    runtimeSelector.gitToolsAvailable(health.available());
                }
            });
        } else {
            runtimeSelector.gitDisabled();
        }
        return state;
    }

    private void registerLifecycleEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::serverStopping);
        ServerLifecycleEvents.AFTER_SAVE.register(this::afterSave);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::serverStopped);
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(this::clientStopping);
    }

    private void serverStarted(MinecraftServer server) {
        if (closed.get() || !(server instanceof IntegratedServer integrated)) {
            return;
        }
        synchronized (lifecycleLock) {
            activeServer = integrated;
            stoppingServer = null;
            liveWorld = null;
            scheduleState = null;
            liveWorldRevision++;
        }
        ensureLiveWorldResolution();
    }

    private void ensureLiveWorldResolution() {
        IntegratedServer server;
        long revision;
        synchronized (lifecycleLock) {
            if (closed.get() || activeServer == null || liveWorld != null) {
                return;
            }
            server = activeServer;
            revision = ++liveWorldRevision;
        }
        final BackupWorldSelection selection;
        try {
            selection = selectionFor(server);
        } catch (RuntimeException exception) {
            logFailure("Integrated world path could not be resolved", exception);
            return;
        }
        submit(() -> resolveWorldBlocking(selection)).whenComplete((resolved, throwable) -> {
            if (throwable != null) {
                logFailure("Integrated world identity could not be resolved", throwable);
                return;
            }
            synchronized (lifecycleLock) {
                if (closed.get()
                        || activeServer != server
                        || liveWorldRevision != revision
                        || resolved.isEmpty()) {
                    return;
                }
                liveWorld = resolved.orElseThrow();
                resetScheduleLocked(stateRegistry.currentOrNull(), liveWorld);
            }
        });
    }

    private void serverStopping(MinecraftServer server) {
        if (closed.get() || !(server instanceof IntegratedServer integrated)) {
            return;
        }
        synchronized (lifecycleLock) {
            if (activeServer != integrated) {
                return;
            }
            stoppingServer = integrated;
        }

        BackupWorldContext world = liveWorldFor(integrated);
        Throwable resolutionFailure = null;
        if (world == null) {
            try {
                world = resolveWorldBlocking(selectionFor(integrated)).orElse(null);
            } catch (IOException | RuntimeException exception) {
                resolutionFailure = exception;
                logFailure("Exit backup world identity could not be resolved", exception);
            }
        }
        if (world == null) {
            observeExitResult(
                    null,
                    resolutionFailure == null
                            ? new IllegalStateException(
                                    "Exit backup world identity was unavailable")
                            : resolutionFailure);
            return;
        }
        RuntimeConfigurationGate.Permit permit = configurationGate.enterBackup();
        boolean transferred = false;
        try {
            RuntimeState state = stateRegistry.currentOrNull();
            if (state == null || closed.get()) {
                observeExitResult(
                        null,
                        new IllegalStateException(
                                "World-exit backup runtime was unavailable"));
                return;
            }
            Optional<String> storageIssue = storageIssue(state);
            if (storageIssue.isPresent()) {
                observeExitResult(
                        null,
                        new IllegalStateException(storageIssue.orElseThrow()));
                return;
            }
            CreateBackupRequest request = request(
                    world,
                    Optional.empty(),
                    BackupTrigger.WORLD_EXIT);
            if (!state.enabledDestinations(request)) {
                return;
            }

            PendingLiveBackup exit = new PendingLiveBackup(
                    state,
                    integrated,
                    request,
                    ProgressListener.NO_OP,
                    new CompletableFuture<>());
            PendingLiveBackup displaced;
            synchronized (lifecycleLock) {
                if (activeServer != integrated || stoppingServer != integrated) {
                    return;
                }
                displaced = liveSaveGate.replaceWithExit(exit).orElse(null);
            }
            exit.settled().whenComplete((ignored, throwable) -> permit.close());
            transferred = true;
            if (displaced != null) {
                displaced.fail(
                        new IllegalStateException("World shutdown replaced the pending manual save"));
            }
            trackExit(exit.result());
        } finally {
            if (!transferred) {
                permit.close();
            }
        }
    }

    private void afterSave(MinecraftServer server, boolean flush, boolean force) {
        PendingLiveBackup pending;
        synchronized (lifecycleLock) {
            pending = liveSaveGate.pending().orElse(null);
            if (pending == null || pending.server() != server) {
                return;
            }
            Optional<PendingLiveBackup> consumed = liveSaveGate.consume(
                    pending,
                    stoppingServer == server,
                    flush,
                    force);
            if (consumed.isEmpty()) {
                return;
            }
            pending = consumed.orElseThrow();
        }
        captureAndDispatch(pending);
    }

    private void serverStopped(MinecraftServer server) {
        PendingLiveBackup abandoned = null;
        boolean shutdownAfterServer;
        synchronized (lifecycleLock) {
            if (activeServer != server && stoppingServer != server) {
                return;
            }
            PendingLiveBackup pending = liveSaveGate.pending().orElse(null);
            if (pending != null && pending.server() == server) {
                abandoned = liveSaveGate.clear(pending).orElse(null);
            }
            activeServer = null;
            stoppingServer = null;
            liveWorld = null;
            scheduleState = null;
            liveWorldRevision++;
            shutdownAfterServer = clientStopping;
        }
        if (abandoned != null) {
            abandoned.fail(
                    new IllegalStateException("The server stopped before its final save completed"));
        }
        if (shutdownAfterServer) {
            shutdown();
        }
    }

    private void clientStopping(Minecraft ignored) {
        boolean noIntegratedServer;
        synchronized (lifecycleLock) {
            clientStopping = true;
            noIntegratedServer = activeServer == null && stoppingServer == null;
        }
        if (noIntegratedServer) {
            shutdown();
        }
    }

    private void clientTick(Minecraft ignored) {
        showRetainedBackgroundWarning();
        RuntimeState state = stateRegistry.currentOrNull();
        if (closed.get() || state == null || !state.config().triggers().scheduledEnabled()) {
            return;
        }
        ScheduleState scheduled;
        BackupWorldContext world;
        IntegratedServer server;
        synchronized (lifecycleLock) {
            scheduled = scheduleState;
            world = liveWorld;
            server = activeServer;
            if (scheduled == null
                    || scheduled.state() != state
                    || world == null
                    || server == null
                    || !scheduled.worldId().equals(world.worldId())
                    || scheduled.schedule().poll(clock.instant()).isEmpty()) {
                return;
            }
            if (liveSaveGate.hasPending()) {
                return;
            }
        }
        withBackupPermit(() -> {
            if (stateRegistry.currentOrNull() != state
                    || busyAcrossStates(world.worldId())) {
                return failedStage("Scheduled backup configuration changed before capture");
            }
            Optional<String> storageIssue = storageIssue(state);
            if (storageIssue.isPresent()) {
                return failedStage(storageIssue.orElseThrow());
            }
            CreateBackupRequest request = request(
                    world,
                    Optional.empty(),
                    BackupTrigger.SCHEDULED);
            if (!state.enabledDestinations(request)) {
                return failedStage("Scheduled backups are disabled for this world");
            }
            return queueRequestedSave(state, server, request, ProgressListener.NO_OP);
        })
                .whenComplete(this::observeScheduledResult);
    }

    private CompletionStage<BackupResult> queueRequestedSave(
            RuntimeState state,
            IntegratedServer server,
            CreateBackupRequest request,
            ProgressListener progressListener) {
        if (closed.get()) {
            return failedStage("WorldArchive is shutting down");
        }
        PendingLiveBackup pending = new PendingLiveBackup(
                state,
                server,
                request,
                progressListener,
                new CompletableFuture<>());
        synchronized (lifecycleLock) {
            if (activeServer != server || stoppingServer == server) {
                return failedStage("The integrated world is closing");
            }
            if (liveSaveGate.hasPending()) {
                return failedStage("Another world save is already pending");
            }
            if (!liveSaveGate.queueRequested(pending)) {
                return failedStage("Another world save is already pending");
            }
        }
        try {
            server.execute(() -> invokeRequestedSave(pending));
        } catch (RejectedExecutionException exception) {
            clearAndFailPending(pending, "The integrated server rejected the backup save");
        }
        return pending.result().minimalCompletionStage();
    }

    private void invokeRequestedSave(PendingLiveBackup pending) {
        synchronized (lifecycleLock) {
            if (liveSaveGate.pending().orElse(null) != pending
                    || stoppingServer == pending.server()) {
                return;
            }
            if (!liveSaveGate.armRequested(pending)) {
                return;
            }
        }
        try {
            pending.server().saveEverything(false, true, true);
        } catch (RuntimeException exception) {
            clearAndFailPending(pending, "The integrated world could not be saved");
            logFailure("Requested world save failed", exception);
        } finally {
            synchronized (lifecycleLock) {
                if (liveSaveGate.clear(pending).isPresent()) {
                    pending.fail(new IllegalStateException(
                            "The requested save produced no matching capture event"));
                }
            }
        }
    }

    private void captureAndDispatch(PendingLiveBackup pending) {
        PreparedBackup prepared;
        try {
            CaptureProgressListener captureProgress = (completed, total) -> pending.state()
                    .coordinator()
                    .currentOperation(pending.request().worldId())
                    .ifPresent(progress -> notifyProgress(pending.progressListener(), progress));
            prepared = pending.state().coordinator().prepareCapture(
                    pending.request(),
                    captureProgress);
        } catch (IOException | InterruptedException | RuntimeException | Error exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            pending.fail(safeFailure(
                    exception,
                    "World capture could not be prepared"));
            if (exception instanceof Error error) {
                throw error;
            }
            return;
        }

        CompletionStage<BackupResult> operation;
        try {
            operation = pending.state().coordinator().createPreparedBackup(
                    prepared,
                    pending.progressListener());
        } catch (RuntimeException | Error exception) {
            try {
                prepared.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            pending.fail(safeFailure(
                    exception,
                    "Prepared backup could not be started"));
            if (exception instanceof Error error) {
                throw error;
            }
            return;
        }
        operation.whenComplete((result, throwable) -> {
            if (throwable != null) {
                pending.fail(throwable);
            } else if (result == null) {
                pending.fail(
                        new IllegalStateException("Backup completed without a result"));
            } else {
                pending.succeed(result);
            }
        });
    }

    private Optional<BackupWorldContext> resolveWorldBlocking(
            BackupWorldSelection selection) throws IOException {
        Path realWorld = selection.worldDirectory().toRealPath();
        if (!realWorld.equals(selection.worldDirectory())) {
            return Optional.empty();
        }
        List<Path> safeWorlds = WorldFolderDiscovery.discover(selection.worldsDirectory());
        if (safeWorlds.stream().noneMatch(realWorld::equals)) {
            return Optional.empty();
        }
        WorldId worldId = identityStore.loadOrCreate(realWorld);
        if (!registerDiscoveredWorldPath(worldId, realWorld)) {
            return Optional.empty();
        }
        return Optional.of(new BackupWorldContext(worldId, selection));
    }

    private static BackupWorldSelection selectionFor(IntegratedServer server) {
        Path worldDirectory = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath()
                .normalize();
        Path worldsDirectory = worldDirectory.getParent();
        Path fileName = worldDirectory.getFileName();
        if (worldsDirectory == null || fileName == null) {
            throw new IllegalStateException("Integrated world path has no storage parent");
        }
        return new BackupWorldSelection(
                worldDirectory,
                worldsDirectory,
                fileName.toString(),
                server.getWorldData().getLevelName());
    }

    private void reconcileWorldPathsAndSchedule(RuntimeState state) {
        synchronized (lifecycleLock) {
            for (WorldConfig configured : state.config().worlds()) {
                worldPaths.configure(configured.worldId(), configured.path());
            }
            if (liveWorld != null
                    && !matchesKnownWorld(
                            liveWorld.worldId(),
                            liveWorld.worldDirectory(),
                            state.config())) {
                liveWorld = null;
                liveWorldRevision++;
            }
            resetScheduleLocked(state, liveWorld);
        }
    }

    private void resetScheduleLocked(
            RuntimeState state,
            BackupWorldContext world) {
        if (state == null || world == null || activeServer == null) {
            scheduleState = null;
            return;
        }
        scheduleState = new ScheduleState(
                state,
                world.worldId(),
                NoCatchUpSchedule.minutes(
                        state.config().triggers().scheduleIntervalMinutes(),
                        clock.instant()));
    }

    private BackupWorldContext liveWorldFor(IntegratedServer server) {
        synchronized (lifecycleLock) {
            return activeServer == server ? liveWorld : null;
        }
    }

    private LiveMatch liveMatch(BackupWorldContext world) {
        synchronized (lifecycleLock) {
            if (activeServer == null
                    || liveWorld == null
                    || !liveWorld.worldId().equals(world.worldId())
                    || !liveWorld.worldDirectory().equals(world.worldDirectory())) {
                return null;
            }
            return new LiveMatch(activeServer);
        }
    }

    private boolean busyAcrossStates(WorldId worldId) {
        synchronized (lifecycleLock) {
            PendingLiveBackup pending = liveSaveGate.pending().orElse(null);
            if (pending != null && pending.request().worldId().equals(worldId)) {
                return true;
            }
        }
        return stateRegistry.retained().stream()
                .anyMatch(state -> state.coordinator().isBusy(worldId));
    }

    private static CreateBackupRequest request(
            BackupWorldContext world,
            Optional<String> label,
            BackupTrigger trigger) {
        return new CreateBackupRequest(
                world.worldId(),
                world.worldDirectory(),
                world.displayName(),
                label,
                trigger);
    }

    private boolean registerWorldPath(
            WorldId worldId,
            Path worldDirectory,
            RuntimeState state) {
        Path world = worldDirectory.toAbsolutePath().normalize();
        if (state != null && !matchesConfiguredWorld(worldId, world, state.config())) {
            return false;
        }
        return worldPaths.isRegistered(worldId, world);
    }

    private boolean matchesKnownWorld(
            WorldId worldId,
            Path worldDirectory,
            WorldArchiveConfig config) {
        Path world = worldDirectory.toAbsolutePath().normalize();
        if (!matchesConfiguredWorld(worldId, world, config)) {
            return false;
        }
        return worldPaths.matches(worldId, world);
    }

    private static boolean matchesConfiguredWorld(
            WorldId worldId,
            Path worldDirectory,
            WorldArchiveConfig config) {
        Optional<WorldConfig> configuredIdentity = config.worlds().stream()
                .filter(world -> world.worldId().equals(worldId))
                .findFirst();
        if (configuredIdentity.isPresent()
                && !configuredIdentity.orElseThrow().path().equals(worldDirectory)) {
            return false;
        }
        return config.worlds().stream()
                .noneMatch(world -> world.path().equals(worldDirectory)
                        && !world.worldId().equals(worldId));
    }

    private boolean registerDiscoveredWorldPath(
            WorldId worldId,
            Path worldDirectory) {
        Path world = worldDirectory.toAbsolutePath().normalize();
        RuntimeConfigurationGate.Permit permit = configurationGate.enterConfigurationChange();
        try {
            RuntimeState current = stateRegistry.currentOrNull();
            if ((current == null || matchesConfiguredWorld(worldId, world, current.config()))
                    && worldPaths.isRegistered(worldId, world)) {
                registerSettingsWorld(worldId, world);
                return true;
            }
            return registerDiscoveredWorldPathHeld(worldId, world, current);
        } finally {
            permit.close();
        }
    }

    private boolean registerDiscoveredWorldPathHeld(
            WorldId worldId,
            Path world,
            RuntimeState state) {
        if (state != null && !matchesConfiguredWorld(worldId, world, state.config())) {
            return false;
        }
        if (!worldPaths.register(worldId, world)) {
            return false;
        }
        if (state != null) {
            refreshStorageSafety(state);
        }
        registerSettingsWorld(worldId, world);
        return true;
    }

    private void registerSettingsWorld(WorldId worldId, Path worldDirectory) {
        CompletionStage<WorldArchiveConfig> registration;
        try {
            registration = ClientSettingsAccess.registerWorld(worldId, worldDirectory);
        } catch (RuntimeException exception) {
            worldSettingsFailures.add(worldId);
            logFailure("World settings could not be updated", exception);
            return;
        }
        registration.whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                worldSettingsFailures.remove(worldId);
                return;
            }
            worldSettingsFailures.add(worldId);
            logFailure("World settings could not be updated", throwable);
        });
    }

    private Optional<String> worldSettingsWarning() {
        return worldSettingsFailures.isEmpty()
                ? Optional.empty()
                : Optional.of("World settings could not be saved; review WorldArchive settings");
    }

    private void clearPersistedWorldSettingsFailures(WorldArchiveConfig config) {
        config.worlds().stream()
                .map(WorldConfig::worldId)
                .forEach(worldSettingsFailures::remove);
    }

    private void refreshStorageSafety(RuntimeState state) {
        if (stateRegistry.currentOrNull() == state) {
            storageSafety.refresh(state.config(), worldPaths.snapshotPaths());
        }
    }

    private Optional<String> storageIssue(RuntimeState state) {
        Optional<String> issue = RuntimeStorageSafety.issue(
                state.config(), worldPaths.snapshotPaths());
        if (stateRegistry.currentOrNull() == state) {
            storageSafety.refresh(state.config(), worldPaths.snapshotPaths());
        }
        return issue;
    }

    private Path managedPath(
            RuntimeState state,
            BackupWorldContext world,
            Optional<BackupRow> selected) {
        if (selected.isPresent() && selected.orElseThrow().zip().durable()) {
            return state.storagePaths().zipDirectory().resolve(world.worldId().toString());
        }
        if (selected.isPresent() && selected.orElseThrow().git().durable()) {
            return state.storagePaths().gitRepository();
        }
        if (state.config().zip().enabled()) {
            return state.storagePaths().zipDirectory();
        }
        return state.storagePaths().gitRepository();
    }

    private Optional<String> validatedRestoredStorageName(RestoreBackupResult result) {
        try {
            Path restored = result.restoredWorldDirectory().toAbsolutePath().normalize();
            Path base = minecraft.getLevelSource().getBaseDir().toAbsolutePath().normalize();
            Path fileName = restored.getFileName();
            if (fileName == null
                    || !base.equals(restored.getParent())
                    || !Files.isDirectory(restored, LinkOption.NOFOLLOW_LINKS)) {
                logFailure(
                        "Restored world path could not be validated",
                        new IOException("Restored world is outside the active saves directory"));
                return Optional.empty();
            }
            return Optional.of(fileName.toString());
        } catch (SecurityException exception) {
            logFailure("Restored world path could not be validated", exception);
            return Optional.empty();
        }
    }

    private void openBrowserForBackup(BackupId backupId, CommandAction action) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(action, "action");
        RuntimeState state = stateRegistry.currentOrNull();
        if (state == null || closed.get()) {
            return;
        }
        state.coordinator().findBackup(backupId).whenComplete((record, throwable) -> {
            if (throwable != null || record == null || record.isEmpty()) {
                if (throwable != null) {
                    logFailure(action + " target could not be loaded", throwable);
                }
                return;
            }
            contextForRecord(state, record.orElseThrow()).whenComplete((context, contextFailure) -> {
                if (contextFailure != null || context == null || context.isEmpty()) {
                    if (contextFailure != null) {
                        logFailure(action + " world could not be resolved", contextFailure);
                    }
                    return;
                }
                minecraft.execute(() -> {
                    if (!closed.get()) {
                        PauseScreen parent = new PauseScreen(true);
                        BackupBrowserScreen browser = switch (action) {
                            case RESTORE -> BackupBrowserScreen.forRestore(
                                    parent,
                                    context.orElseThrow(),
                                    this,
                                    backupId);
                            case DELETE -> BackupBrowserScreen.forDelete(
                                    parent,
                                    context.orElseThrow(),
                                    this,
                                    backupId);
                        };
                        minecraft.setScreenAndShow(browser);
                    }
                });
            });
        });
    }

    private CompletionStage<Optional<BackupWorldContext>> contextForRecord(
            RuntimeState state,
            BackupRecord record) {
        BackupWorldContext missingSource = missingSourceContext(record);
        BackupWorldContext active;
        synchronized (lifecycleLock) {
            active = liveWorld;
        }
        if (active != null && active.worldId().equals(record.manifest().worldId())) {
            return CompletableFuture.completedFuture(Optional.of(active));
        }
        Optional<WorldConfig> configured = state.config().worlds().stream()
                .filter(world -> world.worldId().equals(record.manifest().worldId()))
                .findFirst();
        if (configured.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of(missingSource));
        }
        Path path = configured.orElseThrow().path();
        Path parent = path.getParent();
        Path fileName = path.getFileName();
        if (parent == null || fileName == null) {
            return CompletableFuture.completedFuture(Optional.of(missingSource));
        }
        try {
            return resolveWorld(new BackupWorldSelection(
                    path,
                    parent,
                    fileName.toString(),
                    record.manifest().worldName()))
                    .handle((resolved, throwable) -> resolved != null && resolved.isPresent()
                            ? resolved
                            : Optional.of(missingSource));
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(Optional.of(missingSource));
        }
    }

    private BackupWorldContext missingSourceContext(BackupRecord record) {
        Path worldsDirectory = minecraft.getLevelSource().getBaseDir()
                .toAbsolutePath()
                .normalize();
        String storageName = ".worldarchive-missing-" + record.manifest().worldId();
        BackupWorldContext context = new BackupWorldContext(
                record.manifest().worldId(),
                worldsDirectory.resolve(storageName),
                worldsDirectory,
                storageName,
                record.manifest().worldName());
        return actionContexts.markActionOnly(context);
    }

    private boolean sourceDirectoryAvailable(BackupWorldContext world) {
        if (!actionContexts.sourceActionsAllowed(world)) {
            return false;
        }
        try {
            return Files.isDirectory(world.worldDirectory(), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(
                            world.worldDirectory().resolve("level.dat"),
                            LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException exception) {
            return false;
        }
    }

    private void clearAndFailPending(PendingLiveBackup pending, String message) {
        synchronized (lifecycleLock) {
            liveSaveGate.clear(pending);
        }
        pending.fail(new IllegalStateException(message));
    }

    private void trackExit(CompletableFuture<BackupResult> result) {
        exitWork.add(result);
        result.whenComplete((value, throwable) -> {
            observeExitResult(value, throwable);
            exitWork.remove(result);
        });
    }

    private void observeScheduledResult(BackupResult result, Throwable throwable) {
        Optional<String> warning = BackgroundBackupWarnings.scheduled(result, throwable);
        if (warning.isEmpty()) {
            backgroundWarning.set(Optional.empty());
            return;
        }
        backgroundWarning.set(warning);
        if (!closed.get()) {
            minecraft.execute(() -> {
                if (!closed.get()) {
                    showClientWarning(warning.orElseThrow());
                }
            });
        }
    }

    private void observeExitResult(BackupResult result, Throwable throwable) {
        Optional<String> warning = BackgroundBackupWarnings.worldExit(result, throwable);
        try {
            if (warning.isPresent()) {
                noticeStore.retain(warning.orElseThrow());
            }
        } catch (IOException exception) {
            logFailure("World-exit backup notice could not be stored", exception);
        }
        if (warning.isPresent() && !closed.get()) {
            backgroundWarning.set(warning);
            minecraft.execute(() -> {
                if (!closed.get()) {
                    showClientWarning(warning.orElseThrow());
                }
            });
        }
    }

    private void showRetainedBackgroundWarning() {
        if (closed.get()) {
            return;
        }
        Optional<String> retained = retainedStartupWarning.get();
        if (retained.isEmpty() || !retainedWarningShown.compareAndSet(false, true)) {
            return;
        }
        showClientWarning(retained.orElseThrow());
        retainedStartupWarning.compareAndSet(retained, Optional.empty());
        try {
            noticeStore.clear();
        } catch (IOException exception) {
            logFailure("Background backup notice could not be cleared", exception);
        }
    }

    private void showClientWarning(String message) {
        minecraft.gui.chatListener().handleSystemMessage(
                Component.literal("WorldArchive: " + message)
                        .withStyle(ChatFormatting.YELLOW),
                false);
    }

    private RuntimeState requireCurrentState() {
        RuntimeState state = stateRegistry.currentOrNull();
        if (state == null || closed.get()) {
            throw new IllegalStateException("WorldArchive is still loading");
        }
        return state;
    }

    private boolean unavailable() {
        return closed.get() || stateRegistry.currentOrNull() == null;
    }

    private <T> CompletionStage<T> withBackupPermit(
            Supplier<CompletionStage<T>> operation) {
        RuntimeConfigurationGate.Permit permit = configurationGate.enterBackup();
        try {
            CompletionStage<T> stage = Objects.requireNonNull(
                    operation.get(),
                    "backup operation result");
            stage.whenComplete((ignored, throwable) -> permit.close());
            return stage;
        } catch (RuntimeException | Error exception) {
            permit.close();
            throw exception;
        }
    }

    private <T> CompletionStage<T> submit(Callable<T> operation) {
        if (closed.get()) {
            return failedStage("WorldArchive is shutting down");
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            workerExecutor.execute(() -> {
                try {
                    result.complete(operation.call());
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new IllegalStateException("WorldArchive is shutting down"));
        }
        return result;
    }

    private static <T> CompletionStage<T> failedStage(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    private static void notifyProgress(
            ProgressListener listener,
            OperationProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException exception) {
            // Observers never control capture correctness.
        }
    }

    private static RuntimeException safeFailure(Throwable throwable, String fallback) {
        String message = safeMessage(throwable, fallback);
        return new CompletionException(message, throwable);
    }

    private void logFailure(String fallback, Throwable throwable) {
        LOGGER.warn("{}: {}", fallback, safeMessage(throwable, fallback));
    }

    private static String safeMessage(Throwable throwable, String fallback) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String raw = current.getMessage();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String redacted = SensitiveDataRedactor.redact(raw)
                .replaceAll("\\p{Cntrl}+", " ")
                .strip();
        if (redacted.isEmpty()) {
            return fallback;
        }
        return redacted.length() <= 512 ? redacted : redacted.substring(0, 512);
    }

    private final class RuntimeCoordinator implements BackupCoordinator {
        @Override
        public PreparedBackup prepareCapture(
                CreateBackupRequest request,
                CaptureProgressListener progressListener)
                throws IOException, InterruptedException {
            RuntimeConfigurationGate.Permit permit = configurationGate.enterBackup();
            boolean transferred = false;
            try {
                RuntimeState state = stateRegistry.currentOrNull();
                if (state == null || closed.get()) {
                    throw new IOException("WorldArchive is still loading");
                }
                if (!registerWorldPath(request.worldId(), request.worldDirectory(), state)) {
                    throw new IOException("The world identity is registered to a different folder");
                }
                Optional<String> storageIssue = storageIssue(state);
                if (storageIssue.isPresent()) {
                    throw new IOException(storageIssue.orElseThrow());
                }
                PreparedBackup prepared = state.coordinator().prepareCapture(
                        request,
                        progressListener);
                PreparedOwnership ownership = new PreparedOwnership(state, permit);
                if (externallyPrepared.putIfAbsent(prepared, ownership) != null) {
                    prepared.close();
                    throw new IOException("Prepared capture is already registered");
                }
                try {
                    prepared.addReleaseObserver(
                            () -> releaseAbandonedPrepared(prepared, ownership));
                } catch (RuntimeException | Error exception) {
                    externallyPrepared.remove(prepared, ownership);
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

        private void releaseAbandonedPrepared(
                PreparedBackup prepared,
                PreparedOwnership ownership) {
            if (externallyPrepared.remove(prepared, ownership)) {
                ownership.permit().close();
            }
        }

        @Override
        public CompletionStage<BackupResult> createPreparedBackup(
                PreparedBackup preparedBackup,
                ProgressListener progressListener) {
            Objects.requireNonNull(preparedBackup, "preparedBackup");
            Objects.requireNonNull(progressListener, "progressListener");
            PreparedOwnership ownership = externallyPrepared.remove(preparedBackup);
            if (ownership == null) {
                return failedStage("Prepared capture does not belong to this runtime");
            }
            Optional<String> storageIssue = storageIssue(ownership.state());
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
            return withBackupPermit(() -> {
                RuntimeState state = stateRegistry.currentOrNull();
                if (state == null || closed.get()) {
                    return failedStage("WorldArchive is still loading");
                }
                if (!registerWorldPath(request.worldId(), request.worldDirectory(), state)) {
                    return failedStage("The world identity is registered to a different folder");
                }
                Optional<String> storageIssue = storageIssue(state);
                if (storageIssue.isPresent()) {
                    return failedStage(storageIssue.orElseThrow());
                }
                return state.coordinator().createBackup(request, progressListener);
            });
        }

        @Override
        public Optional<OperationProgress> currentOperation(WorldId worldId) {
            Objects.requireNonNull(worldId, "worldId");
            if (closed.get()) {
                return Optional.empty();
            }
            RuntimeState current = stateRegistry.currentOrNull();
            if (current != null) {
                Optional<OperationProgress> active = current.coordinator().currentOperation(worldId);
                if (active.isPresent()) {
                    return active;
                }
            }
            return stateRegistry.retained().stream()
                    .filter(state -> state != current)
                    .map(state -> state.coordinator().currentOperation(worldId))
                    .flatMap(Optional::stream)
                    .findFirst();
        }

        @Override
        public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
            RuntimeState state = stateRegistry.currentOrNull();
            return state == null || closed.get()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().listBackups(worldId);
        }

        @Override
        public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
            RuntimeState state = stateRegistry.currentOrNull();
            return state == null || closed.get()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().findBackup(backupId);
        }

        @Override
        public CompletionStage<RestoreBackupResult> restoreBackup(
                RestoreBackupRequest request,
                ProgressListener progressListener) {
            RuntimeConfigurationGate.Permit operationPermit = configurationGate.enterBackup();
            RuntimeState state = stateRegistry.currentOrNull();
            if (state == null || closed.get()) {
                operationPermit.close();
                return failedStage("WorldArchive is still loading");
            }
            Optional<String> storageIssue = storageIssue(state);
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
            CompletableFuture<RestoreBackupResult> completion = new CompletableFuture<>();
            operation.whenComplete((result, throwable) -> {
                if (throwable != null || result == null) {
                    operationPermit.close();
                    completion.completeExceptionally(throwable == null
                            ? new IllegalStateException("Restore completed without a result")
                            : throwable);
                    return;
                }
                RuntimeConfigurationGate.Permit registrationPermit = null;
                Throwable registrationFailure = null;
                try {
                    registrationPermit = configurationGate
                            .transitionBackupToConfigurationChange(operationPermit);
                    Path restored = result.restoredWorldDirectory()
                            .toAbsolutePath()
                            .normalize();
                    if (!registerDiscoveredWorldPathHeld(
                            result.restoredWorldId(), restored, stateRegistry.currentOrNull())) {
                        throw new IllegalStateException(
                                "The restored world identity is registered to another folder");
                    }
                } catch (RuntimeException | Error exception) {
                    operationPermit.close();
                    registrationFailure = exception;
                } finally {
                    if (registrationPermit != null) {
                        registrationPermit.close();
                    }
                }
                if (registrationFailure == null) {
                    completion.complete(result);
                } else {
                    completion.completeExceptionally(registrationFailure);
                }
            });
            return completion;
        }

        @Override
        public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
            RuntimeState state = stateRegistry.currentOrNull();
            return state == null || closed.get()
                    ? failedStage("WorldArchive is still loading")
                    : state.coordinator().prepareDelete(backupId);
        }

        @Override
        public CompletionStage<BackupResult> deleteBackup(
                DeleteBackupRequest request,
                ProgressListener progressListener) {
            return withBackupPermit(() -> {
                RuntimeState state = stateRegistry.currentOrNull();
                if (state == null || closed.get()) {
                    return failedStage("WorldArchive is still loading");
                }
                Optional<String> storageIssue = storageIssue(state);
                return storageIssue.isPresent()
                        ? failedStage(storageIssue.orElseThrow())
                        : state.coordinator().deleteBackup(request, progressListener);
            });
        }

        @Override
        public CompletionStage<BackupResult> verifyBackup(
                BackupId backupId,
                ProgressListener progressListener) {
            return withBackupPermit(() -> {
                RuntimeState state = stateRegistry.currentOrNull();
                if (state == null || closed.get()) {
                    return failedStage("WorldArchive is still loading");
                }
                Optional<String> storageIssue = storageIssue(state);
                return storageIssue.isPresent()
                        ? failedStage(storageIssue.orElseThrow())
                        : state.coordinator().verifyBackup(backupId, progressListener);
            });
        }

        @Override
        public CompletionStage<BackupResult> syncBackup(
                BackupId backupId,
                ProgressListener progressListener) {
            return withBackupPermit(() -> {
                RuntimeState state = stateRegistry.currentOrNull();
                if (state == null || closed.get()) {
                    return failedStage("WorldArchive is still loading");
                }
                Optional<String> storageIssue = storageIssue(state);
                return storageIssue.isPresent()
                        ? failedStage(storageIssue.orElseThrow())
                        : state.coordinator().syncBackup(backupId, progressListener);
            });
        }

        @Override
        public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
            return withBackupPermit(() -> {
                RuntimeState state = stateRegistry.currentOrNull();
                if (state == null || closed.get()) {
                    return failedStage("WorldArchive is still loading");
                }
                if (storageIssue(state).isPresent()) {
                    return CompletableFuture.completedFuture(storageAwareHealth(
                            state.config(), disabledHealth(state.config())));
                }
                if (!state.config().git().enabled() && !state.config().zip().enabled()) {
                    return CompletableFuture.completedFuture(disabledHealth(state.config()));
                }
                return state.coordinator().health(worldId).thenApply(health -> {
                    health.stream()
                            .filter(item -> item.destination() == DestinationType.GIT)
                            .findFirst()
                            .ifPresent(item -> {
                                if (item.status() == DestinationHealthStatus.HEALTHY) {
                                    state.selector().gitToolsAvailable(true);
                                } else if (item.status() == DestinationHealthStatus.TOOL_MISSING) {
                                    state.selector().gitToolsAvailable(false);
                                }
                            });
                    return storageAwareHealth(
                            state.config(), configuredHealth(state.config(), health));
                });
            });
        }
    }

    private List<DestinationHealth> storageAwareHealth(
            WorldArchiveConfig config,
            List<DestinationHealth> health) {
        Optional<String> issue = storageSafety.warning();
        if (issue.isEmpty()) {
            return health;
        }
        return health.stream()
                .map(item -> {
                    boolean enabled = item.destination() == DestinationType.GIT
                            ? config.git().enabled()
                            : config.zip().enabled();
                    return enabled
                            ? new DestinationHealth(
                                    item.destination(),
                                    DestinationHealthStatus.UNAVAILABLE,
                                    issue.orElseThrow(),
                                    clock.instant())
                            : item;
                })
                .toList();
    }

    private List<DestinationHealth> configuredHealth(
            WorldArchiveConfig config,
            List<DestinationHealth> health) {
        List<DestinationHealth> configured = new ArrayList<>(health.size());
        for (DestinationHealth item : health) {
            boolean enabled = item.destination() == DestinationType.GIT
                    ? config.git().enabled()
                    : config.zip().enabled();
            configured.add(enabled
                    ? item
                    : new DestinationHealth(
                            item.destination(),
                            DestinationHealthStatus.DISABLED,
                            item.destination() + " destination is disabled",
                            clock.instant()));
        }
        return List.copyOf(configured);
    }

    private List<DestinationHealth> disabledHealth(WorldArchiveConfig config) {
        return configuredHealth(config, List.of(
                DestinationHealth.notChecked(DestinationType.GIT),
                DestinationHealth.notChecked(DestinationType.ZIP)));
    }

    private record PreparedOwnership(
            RuntimeState state,
            RuntimeConfigurationGate.Permit permit) {
        private PreparedOwnership {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(permit, "permit");
        }
    }

    private record RuntimeState(
            WorldArchiveConfig config,
            RuntimeStoragePaths storagePaths,
            GitBackupBackend gitBackend,
            RuntimeDestinationSelector selector,
            SerializedBackupCoordinator coordinator) {
        private RuntimeState {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(storagePaths, "storagePaths");
            Objects.requireNonNull(gitBackend, "gitBackend");
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(coordinator, "coordinator");
        }

        private boolean enabledDestinations(CreateBackupRequest request) {
            return !selector.select(request).isEmpty();
        }

        private void close() {
            gitBackend.close();
        }
    }

    private record ScheduleState(
            RuntimeState state,
            WorldId worldId,
            NoCatchUpSchedule schedule) {
        private ScheduleState {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(schedule, "schedule");
        }
    }

    private static final class PendingLiveBackup {
        private final RuntimeState state;

        private final IntegratedServer server;

        private final CreateBackupRequest request;

        private final ProgressListener progressListener;

        private final CompletableFuture<BackupResult> result;

        private final CompletableFuture<Void> settled = new CompletableFuture<>();

        private PendingLiveBackup(
                RuntimeState state,
                IntegratedServer server,
                CreateBackupRequest request,
                ProgressListener progressListener,
                CompletableFuture<BackupResult> result) {
            this.state = Objects.requireNonNull(state, "state");
            this.server = Objects.requireNonNull(server, "server");
            this.request = Objects.requireNonNull(request, "request");
            this.progressListener = Objects.requireNonNull(progressListener, "progressListener");
            this.result = Objects.requireNonNull(result, "result");
        }

        private RuntimeState state() {
            return state;
        }

        private IntegratedServer server() {
            return server;
        }

        private CreateBackupRequest request() {
            return request;
        }

        private ProgressListener progressListener() {
            return progressListener;
        }

        private CompletableFuture<BackupResult> result() {
            return result;
        }

        private CompletableFuture<Void> settled() {
            return settled;
        }

        private void succeed(BackupResult value) {
            result.complete(value);
            settled.complete(null);
        }

        private void fail(Throwable throwable) {
            result.completeExceptionally(throwable);
            settled.complete(null);
        }

    }

    private record LiveMatch(IntegratedServer server) {
        private LiveMatch {
            Objects.requireNonNull(server, "server");
        }
    }

    private enum CommandAction {
        RESTORE,
        DELETE
    }
}
