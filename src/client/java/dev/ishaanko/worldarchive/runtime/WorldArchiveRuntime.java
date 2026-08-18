package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.WorldArchiveMetadata;
import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.core.BackupCoordinator;
import dev.ishaanko.worldarchive.core.BackupService;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaanko.worldarchive.core.FileWorldInventoryStore;
import dev.ishaanko.worldarchive.core.LockingWorldOperationGate;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.OperationProgress;
import dev.ishaanko.worldarchive.core.PreparedBackup;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.importing.BackupImportService;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.DestinationHealthStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.settings.ClientSettingsAccess;
import dev.ishaanko.worldarchive.settings.SettingsDefaults;
import dev.ishaanko.worldarchive.settings.WorldFolderDiscovery;
import dev.ishaanko.worldarchive.ui.BackupClientFacade;
import dev.ishaanko.worldarchive.ui.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.BackupWorldEntry;
import dev.ishaanko.worldarchive.ui.BackupWorldSelection;
import dev.ishaanko.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.CleanupRequest;
import dev.ishaanko.worldarchive.storage.management.CleanupResult;
import dev.ishaanko.worldarchive.storage.management.StorageOverview;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-owned service graph, lifecycle save gate, and native-screen facade. */
public final class WorldArchiveRuntime implements BackupClientFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldArchiveMetadata.MOD_NAME);

    private static final Duration CLIENT_SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private static final AtomicReference<WorldArchiveRuntime> INSTANCE = new AtomicReference<>();

    private final Minecraft minecraft;

    private final Path storageRoot;

    private final BackupCatalog catalog;

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

    private final RuntimeConfigurationGate configurationGate;

    private final RuntimeActionContextRegistry actionContexts =
            new RuntimeActionContextRegistry();

    private final BackupCoordinator coordinatorView = new RuntimeBackupCoordinator(this);

    private final BackupService serviceView = new RuntimeBackupService(this, coordinatorView);

    private final BackupImportService importsView = new RuntimeBackupImportService(this);

    private final RuntimeStorageCoordinator storageView = new RuntimeStorageCoordinator(this);

    private final RuntimeNavigation navigation = new RuntimeNavigation(this);

    private final AtomicBoolean closed = new AtomicBoolean();

    private final RuntimeBackgroundBackupMonitor backgroundBackups;

    private final Set<WorldId> worldSettingsFailures = ConcurrentHashMap.newKeySet();

    private final Object stateLock = new Object();

    private final RuntimeServices services;

    private final RuntimeClientFacade clientFacade;

    private WorldArchiveRuntime(Minecraft minecraft) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.configurationGate = new RuntimeConfigurationGate(this::retireInactiveStates);
        this.storageRoot = minecraft.gameDirectory.toPath()
                .toAbsolutePath()
                .normalize()
                .resolve("worldarchive");
        this.backgroundBackups = new RuntimeBackgroundBackupMonitor(
                minecraft,
                storageRoot.resolve("last-background-warning.txt"),
                closed::get,
                this::logFailure);
        this.catalog = new FileBackupCatalog(storageRoot.resolve("catalog.json"));
        this.inventoryStore = new FileWorldInventoryStore(storageRoot.resolve("inventories"));
        this.captureFactory = new FileSystemBackupCaptureFactory(
                storageRoot.resolve("capture-temp"),
                RunningGameVersion.current());
        this.services = new RuntimeServices(
                minecraft,
                storageRoot,
                catalog,
                inventoryStore,
                captureFactory,
                identityStore,
                captureMutex,
                operationGate,
                workerExecutor,
                clock);
        this.clientFacade = new RuntimeClientFacade(
                this,
                serviceView,
                importsView,
                storageView,
                navigation,
                lifecycle,
                backgroundBackups);
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
        RuntimeState replacement;
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
            replacement = buildState(resolved);
            stateRegistry.install(replacement);
            lifecycle.reconcile(replacement);
            refreshStorageSafety(replacement);
            clearPersistedWorldSettingsFailures(resolved);
        }
        primeState(replacement);
        lifecycle.ensureLiveWorldResolution();
    }

    private void primeState(RuntimeState state) {
        RuntimeConfigurationGate.Permit permit = configurationGate.retainStateWork();
        try {
            CompletionStage<Void> prime = new RuntimeStateFactory(this).prime(state);
            prime.whenComplete((ignored, throwable) -> permit.close());
        } catch (RuntimeException | Error exception) {
            permit.close();
            throw exception;
        }
    }

    private void retireInactiveStates() {
        if (closed.get()) {
            return;
        }
        stateRegistry.removeRetired().forEach(RuntimeState::close);
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
            List<BackupRecord> records;
            try {
                records = catalog.listAll();
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Destination paths cannot be validated against the backup catalog",
                        exception);
            }
            RuntimeDestinationPathGuard.requireCatalogAllowed(
                    current.storagePaths(), replacement, records);
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
        return backgroundBackups.awaitExitWork(timeout);
    }

    /** Performs bounded exit draining, rejects new work, and releases runtime-owned workers. */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lifecycle.close();
        actionContexts.clear();
        storageView.close();
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

    RuntimeServices services() {
        return services;
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
        return clientFacade.backupService();
    }

    @Override
    public BackupImportService importService() {
        return clientFacade.importService();
    }

    @Override
    public CompletionStage<List<BackupWorldEntry>> backupWorlds() {
        return clientFacade.backupWorlds();
    }

    @Override
    public CompletionStage<Optional<BackupWorldContext>> resolveWorld(
            BackupWorldSelection selection) {
        return clientFacade.resolveWorld(selection);
    }

    @Override
    public CompletionStage<BackupResult> createManualBackup(
            BackupWorldContext world,
            Optional<String> label,
            ProgressListener progressListener) {
        return clientFacade.createManualBackup(world, label, progressListener);
    }

    @Override
    public CompletionStage<BackupBrowserCapabilities> browserCapabilities(
            BackupWorldContext world) {
        return clientFacade.browserCapabilities(world);
    }

    @Override
    public CompletionStage<StorageOverview> storageOverview(WorldId worldId) {
        return clientFacade.storageOverview(worldId);
    }

    @Override
    public CompletionStage<Boolean> claimStorageReviewNotice(WorldId worldId) {
        return clientFacade.claimStorageReviewNotice(worldId);
    }

    @Override
    public CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        return clientFacade.prepareCleanup(worldId);
    }

    @Override
    public CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        return clientFacade.applyCleanup(request);
    }

    @Override
    public CompletionStage<Void> discardCleanup(OperationId confirmationToken) {
        return clientFacade.discardCleanup(confirmationToken);
    }

    @Override
    public CompletionStage<Void> saveStoragePolicy(
            WorldId worldId,
            StoragePolicy policy) {
        return clientFacade.saveStoragePolicy(worldId, policy);
    }

    @Override
    public void openManagedFolder(
            BackupWorldContext world,
            Optional<BackupRow> selectedBackup) {
        clientFacade.openManagedFolder(world, selectedBackup);
    }

    @Override
    public void openSettings(Screen returnTo) {
        clientFacade.openSettings(returnTo);
    }

    @Override
    public void selectRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        clientFacade.selectRestoredWorld(returnTo, result);
    }

    @Override
    public void playRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        clientFacade.playRestoredWorld(returnTo, result);
    }

    public void openBrowser() {
        navigation.openBrowser();
    }

    private RuntimeState buildState(WorldArchiveConfig config) {
        return new RuntimeStateFactory(this).build(config, services);
    }

    Optional<BackupWorldContext> resolveWorldBlocking(
            BackupWorldSelection selection) throws IOException {
        if (!acceptsLiveWorld(selection)) {
            return Optional.empty();
        }
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

    boolean acceptsLiveWorld(BackupWorldSelection selection) {
        return WorldFolderDiscovery.isDirectChild(
                minecraft.gameDirectory.toPath().resolve("saves"),
                selection.worldDirectory());
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

    boolean registerWorldPath(WorldId worldId, Path worldDirectory) {
        // Exact registrations were verified against the folder on disk and stay in
        // sync with every published configuration, so a configuration snapshot that
        // still carries a stale claim cannot veto them.
        return worldPaths.isRegistered(worldId, worldDirectory.toAbsolutePath().normalize());
    }

    boolean matchesKnownWorld(
            WorldId worldId,
            Path worldDirectory,
            WorldArchiveConfig config) {
        Path world = worldDirectory.toAbsolutePath().normalize();
        if (worldPaths.isRegistered(worldId, world)) {
            return true;
        }
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
        if (state != null
                && !matchesConfiguredWorld(worldId, world, state.config())
                && !conflictingConfiguredClaimsAreStale(worldId, world, state.config())) {
            return false;
        }
        if (!worldPaths.register(worldId, world)
                && (!releaseStaleRegistryClaims(worldId, world)
                        || !worldPaths.register(worldId, world))) {
            return false;
        }
        if (state != null) {
            refreshStorageSafety(state);
        }
        registerSettingsWorld(worldId, world);
        return true;
    }

    /**
     * Verifies that every configured claim conflicting with this world no longer
     * matches a folder on disk, so a world restored or moved onto a previously
     * used path is not blocked by the stale entry the reconciler will drop.
     */
    private boolean conflictingConfiguredClaimsAreStale(
            WorldId worldId,
            Path world,
            WorldArchiveConfig config) {
        for (WorldConfig configured : config.worlds()) {
            Path configuredPath = configured.path().toAbsolutePath().normalize();
            boolean sameId = configured.worldId().equals(worldId);
            boolean samePath = configuredPath.equals(world);
            if (sameId && !samePath && !claimStaleOnDisk(configured.worldId(), configuredPath)) {
                return false;
            }
            if (!sameId && samePath && !folderHoldsIdentity(worldId, world)) {
                return false;
            }
        }
        return true;
    }

    private boolean releaseStaleRegistryClaims(WorldId worldId, Path world) {
        Path claimedPath = worldPaths.claimedPath(worldId).orElse(null);
        if (claimedPath != null && !claimedPath.equals(world)) {
            if (!claimStaleOnDisk(worldId, claimedPath)) {
                return false;
            }
            worldPaths.release(worldId, claimedPath);
        }
        WorldId claimedWorld = worldPaths.claimedWorld(world).orElse(null);
        if (claimedWorld != null && !claimedWorld.equals(worldId)) {
            if (!folderHoldsIdentity(worldId, world)) {
                return false;
            }
            worldPaths.release(claimedWorld, world);
        }
        return true;
    }

    /** True when the folder no longer exists or no longer carries the claimed identity. */
    private boolean claimStaleOnDisk(WorldId claimedId, Path claimedPath) {
        if (!Files.isDirectory(claimedPath, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            return identityStore.loadExisting(claimedPath)
                    .map(identity -> !identity.worldId().equals(claimedId))
                    .orElse(true);
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean folderHoldsIdentity(WorldId worldId, Path world) {
        try {
            return identityStore.loadExisting(world)
                    .map(identity -> identity.worldId().equals(worldId))
                    .orElse(false);
        } catch (IOException exception) {
            return false;
        }
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

    Optional<String> worldSettingsWarning() {
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
            storageSafety.set(issue);
        }
        return issue;
    }

    void trackExit(CompletableFuture<BackupResult> result) {
        backgroundBackups.trackExit(result);
    }

    void observeScheduledResult(BackupResult result, Throwable throwable) {
        backgroundBackups.observeScheduledResult(result, throwable);
    }

    void observeExitResult(BackupResult result, Throwable throwable) {
        backgroundBackups.observeExitResult(result, throwable);
    }

    void showRetainedBackgroundWarning() {
        backgroundBackups.showRetainedWarning();
    }

    void beginBackupProgress(String message, Object progressKey) {
        backgroundBackups.beginBackupProgress(message, progressKey);
    }

    ProgressListener backupProgressListener(Object progressKey) {
        return backgroundBackups.backupProgressListener(progressKey);
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
