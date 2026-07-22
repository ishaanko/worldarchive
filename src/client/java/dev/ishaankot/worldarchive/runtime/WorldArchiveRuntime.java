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
import dev.ishaankot.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaankot.worldarchive.core.FileWorldInventoryStore;
import dev.ishaankot.worldarchive.core.LockingWorldOperationGate;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.PreparedBackup;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaankot.worldarchive.model.BackupId;
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
import dev.ishaankot.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaankot.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupBackend;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaankot.worldarchive.ui.BackupClientFacade;
import dev.ishaankot.worldarchive.ui.BackupWorldContext;
import dev.ishaankot.worldarchive.ui.BackupWorldSelection;
import dev.ishaankot.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.io.IOException;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
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

    private final RuntimeLifecycle lifecycle = new RuntimeLifecycle(this, clock);

    private final RuntimeStateRegistry<RuntimeState> stateRegistry = new RuntimeStateRegistry<>();

    private final ConcurrentMap<PreparedBackup, PreparedOwnership> externallyPrepared =
            new ConcurrentHashMap<>();

    private final RuntimeWorldPathRegistry worldPaths = new RuntimeWorldPathRegistry();

    private final RuntimeStorageSafety storageSafety = new RuntimeStorageSafety();

    private final RuntimeConfigurationGate configurationGate = new RuntimeConfigurationGate();

    private final RuntimeActionContextRegistry actionContexts =
            new RuntimeActionContextRegistry();

    private final Set<CompletableFuture<BackupResult>> exitWork = ConcurrentHashMap.newKeySet();

    private final BackupCoordinator coordinatorView = new RuntimeBackupCoordinator(this);

    private final RuntimeNavigation navigation = new RuntimeNavigation(this);

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicReference<Optional<String>> backgroundWarning =
            new AtomicReference<>(Optional.empty());

    private final Set<WorldId> worldSettingsFailures = ConcurrentHashMap.newKeySet();

    private final AtomicReference<Optional<String>> retainedStartupWarning =
            new AtomicReference<>(Optional.empty());

    private final AtomicBoolean retainedWarningShown = new AtomicBoolean();

    private final Object stateLock = new Object();

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
        created.lifecycle.register();
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
            lifecycle.reconcile(replacement);
            refreshStorageSafety(replacement);
            clearPersistedWorldSettingsFailures(resolved);
        }
        lifecycle.ensureLiveWorldResolution();
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
        lifecycle.close();
        actionContexts.clear();
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

    RuntimeConfigurationGate configurationGate() {
        return configurationGate;
    }

    RuntimeStateRegistry<RuntimeState> states() {
        return stateRegistry;
    }

    ConcurrentMap<PreparedBackup, PreparedOwnership> preparedCaptures() {
        return externallyPrepared;
    }

    boolean isClosed() {
        return closed.get();
    }

    Minecraft minecraft() {
        return minecraft;
    }

    ExecutorService workerExecutor() {
        return workerExecutor;
    }

    RuntimeActionContextRegistry actionContexts() {
        return actionContexts;
    }

    RuntimeWorldPathRegistry worldPaths() {
        return worldPaths;
    }

    BackupWorldContext currentLiveWorld() {
        return lifecycle.liveWorld();
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
        return lifecycle.activeWorldId();
    }

    @Override
    public CompletionStage<BackupResult> createManualBackup(
            Optional<String> label,
            ProgressListener progressListener) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(progressListener, "progressListener");
        BackupWorldContext world = lifecycle.liveWorld();
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
        RuntimeState state = stateRegistry.currentOrNull();
        if (state == null || closed.get()) {
            return failedStage("WorldArchive is still loading");
        }
        CreateBackupRequest request = request(world, Optional.empty(), BackupTrigger.MANUAL);
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
        Optional<String> storageIssue = storageIssue(state);
        return CompletableFuture.completedFuture(new BackupBrowserCapabilities(
                busyAcrossStates(world.worldId()),
                storageIssue.isEmpty() && sourceAvailable && createAvailable,
                config.git().enabled() && state.gitBackend().remoteConfigured(world.worldId()),
                storageIssue.isEmpty() && folderAvailable,
                storageIssue.or(() -> worldSettingsWarning()
                        .or(() -> backgroundWarning.get().or(state.selector()::warning)))));
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

    @Override
    public void openBrowser() {
        navigation.openBrowser();
    }

    @Override
    public void openRestore(BackupId backupId) {
        navigation.openRestore(backupId);
    }

    @Override
    public void openDeleteConfirmation(BackupId backupId) {
        navigation.openDeleteConfirmation(backupId);
    }

    @Override
    public void openSettings() {
        navigation.openSettings(new PauseScreen(true));
    }

    private RuntimeState buildState(WorldArchiveConfig config) {
        RuntimeStoragePaths storagePaths = RuntimeStoragePaths.from(config, storageRoot);
        Path gitRepository = storagePaths.gitRepository();
        Path zipDirectory = storagePaths.zipDirectory();
        WorldGitSnapshotStore gitBackend = new WorldGitSnapshotStore(
                GitBackendSettings.from(config.git(), gitRepository),
                GitBackendSettings.legacyFrom(config.git()),
                config.worlds().stream()
                        .filter(world -> world.remoteUrl().isPresent())
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                WorldConfig::worldId,
                                world -> world.remoteUrl().orElseThrow())),
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

    Optional<BackupWorldContext> resolveWorldBlocking(
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

    boolean busyAcrossStates(WorldId worldId) {
        if (lifecycle.hasPending(worldId)) {
            return true;
        }
        return stateRegistry.retained().stream()
                .anyMatch(state -> state.coordinator().isBusy(worldId));
    }

    static CreateBackupRequest request(
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

    boolean registerWorldPath(
            WorldId worldId,
            Path worldDirectory,
            RuntimeState state) {
        Path world = worldDirectory.toAbsolutePath().normalize();
        if (state != null && !matchesConfiguredWorld(worldId, world, state.config())) {
            return false;
        }
        return worldPaths.isRegistered(worldId, world);
    }

    boolean matchesKnownWorld(
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

    boolean registerDiscoveredWorldPathHeld(
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

    Optional<String> storageIssue(RuntimeState state) {
        Optional<String> issue = RuntimeStorageSafety.issue(
                state.config(), worldPaths.snapshotPaths());
        if (stateRegistry.currentOrNull() == state) {
            storageSafety.refresh(state.config(), worldPaths.snapshotPaths());
        }
        return issue;
    }

    void trackExit(CompletableFuture<BackupResult> result) {
        exitWork.add(result);
        result.whenComplete((value, throwable) -> {
            observeExitResult(value, throwable);
            exitWork.remove(result);
        });
    }

    void observeScheduledResult(BackupResult result, Throwable throwable) {
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

    void observeExitResult(BackupResult result, Throwable throwable) {
        if (throwable != null) {
            logFailure("World-exit backup did not complete", throwable);
        } else if (result == null) {
            LOGGER.warn("World-exit backup completed without a result");
        }
        Optional<String> warning = BackgroundBackupWarnings.worldExit(result, throwable);
        BackgroundBackupWarnings.ExitNotice notice =
                BackgroundBackupWarnings.worldExitNotice(result, throwable);
        try {
            if (warning.isPresent()) {
                noticeStore.retain(warning.orElseThrow());
            } else {
                noticeStore.clear();
            }
        } catch (IOException exception) {
            logFailure("World-exit backup notice could not be stored", exception);
        }
        backgroundWarning.set(warning);
        enqueueWorldExitNotice(notice);
    }

    void showRetainedBackgroundWarning() {
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

    private void showWorldExitNotice(BackgroundBackupWarnings.ExitNotice notice) {
        ChatFormatting color = switch (notice.severity()) {
            case SUCCESS -> ChatFormatting.GREEN;
            case WARNING -> ChatFormatting.YELLOW;
            case ERROR -> ChatFormatting.RED;
        };
        SystemToast.addOrUpdate(
                minecraft.gui.toastManager(),
                SystemToast.SystemToastId.WORLD_BACKUP,
                Component.literal("WorldArchive").withStyle(ChatFormatting.BOLD),
                Component.literal(notice.message()).withStyle(color));
    }

    void enqueueWorldExitNotice(BackgroundBackupWarnings.ExitNotice notice) {
        if (closed.get()) {
            return;
        }
        minecraft.execute(() -> {
            if (!closed.get()) {
                showWorldExitNotice(notice);
            }
        });
    }

    RuntimeState requireCurrentState() {
        RuntimeState state = stateRegistry.currentOrNull();
        if (state == null || closed.get()) {
            throw new IllegalStateException("WorldArchive is still loading");
        }
        return state;
    }

    boolean unavailable() {
        return closed.get() || stateRegistry.currentOrNull() == null;
    }

    <T> CompletionStage<T> withBackupPermit(
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

    <T> CompletionStage<T> submit(Callable<T> operation) {
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

    static <T> CompletionStage<T> failedStage(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    static void notifyProgress(
            ProgressListener listener,
            OperationProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException exception) {
            // Observers never control capture correctness.
        }
    }

    static RuntimeException safeFailure(Throwable throwable, String fallback) {
        String message = safeMessage(throwable, fallback);
        return new CompletionException(message, throwable);
    }

    void logFailure(String fallback, Throwable throwable) {
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

    List<DestinationHealth> storageAwareHealth(
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

    List<DestinationHealth> configuredHealth(
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

    List<DestinationHealth> disabledHealth(WorldArchiveConfig config) {
        return configuredHealth(config, List.of(
                DestinationHealth.notChecked(DestinationType.GIT),
                DestinationHealth.notChecked(DestinationType.ZIP)));
    }

}
