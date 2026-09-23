package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.DefaultDestinations;
import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.core.ConfiguredBackupDestinationSelector;
import dev.ishaanko.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaanko.worldarchive.core.FileWorldInventoryStore;
import dev.ishaanko.worldarchive.core.LockingWorldOperationGate;
import dev.ishaanko.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaanko.worldarchive.importing.FileBackupImportService;
import dev.ishaanko.worldarchive.importing.FileImportSourceRegistry;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.recovery.BackupRecoveryService;
import dev.ishaanko.worldarchive.recovery.RestoredWorldMetadataFinalizer;
import dev.ishaanko.worldarchive.storage.git.GitBackendSettings;
import dev.ishaanko.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.management.FileStorageHistoryStore;
import dev.ishaanko.worldarchive.storage.management.ManagedStorageService;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupBackend;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldArchive's backup engine as the game runs it. It keeps what lives as long as the game: the
 * catalog, the change inventories, the world captures, the per-world gates and the worker
 * executor. From each version of the settings it builds a {@link RuntimeState} of services. New
 * work uses the newest state; work that started on an older state finishes there, and older states
 * are closed once no work holds a {@link ConfigurationGate} permit. The e2e tests build the engine
 * through this class as well.
 */
public final class ServiceGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final int MESSAGE_LIMIT = 512;

    private final DefaultDestinations defaults;

    private final FileBackupCatalog catalog;

    private final FileWorldInventoryStore inventories;

    private final FileSystemBackupCaptureFactory captures;

    private final WorldIdentityStore identities = new WorldIdentityStore();

    private final FileImportSourceRegistry importSources;

    private final FileBackupDeletionRegistry deletions;

    private final FileStorageHistoryStore history;

    private final LockingWorldOperationGate captureMutex = new LockingWorldOperationGate();

    private final LockingWorldOperationGate operationGate = new LockingWorldOperationGate();

    private final RestoredWorldMetadataFinalizer finalizer;

    private final ExecutorService executor;

    private final Clock clock;

    private final ConfigurationGate gate;

    /** The state new work uses; written under this object's lock, read without it every client tick. */
    private volatile RuntimeState current;

    // Guarded by this: every state that is not closed yet.
    private final List<RuntimeState> open = new ArrayList<>();

    private boolean closed;

    /**
     * @param storageRoot WorldArchive's folder in the game directory
     * @param captures copies worlds into {@code capture-temp} in {@code storageRoot}
     * @param finalizer gives a restored world the name the player chose
     * @param executor runs all background work; it belongs to the caller, who also stops it
     * @param clock the clock of backup times; its zone decides the local days of the storage history
     */
    public ServiceGraph(
            Path storageRoot,
            FileSystemBackupCaptureFactory captures,
            RestoredWorldMetadataFinalizer finalizer,
            ExecutorService executor,
            Clock clock) {
        Path root = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.captures = Objects.requireNonNull(captures, "captures");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaults = new DefaultDestinations(root);
        this.catalog = new FileBackupCatalog(root.resolve("catalog.json"));
        this.inventories = new FileWorldInventoryStore(root.resolve("inventories"));
        this.importSources = new FileImportSourceRegistry(root.resolve("import-sources.json"));
        this.deletions = new FileBackupDeletionRegistry(root.resolve("deleted-backups.txt"));
        this.history = new FileStorageHistoryStore(root.resolve("storage-history"));
        // The last permit may be released on the render thread, and closing a state deletes its
        // import previews, so a worker closes the retired states.
        this.gate = new ConfigurationGate(() -> AsyncTasks.run(executor, this::closeRetiredStates));
    }

    /** The state new work uses; empty until the settings first load. */
    public Optional<RuntimeState> current() {
        return Optional.ofNullable(current);
    }

    /**
     * Builds the services for {@code config}, with default folders filled in, and routes new work
     * to them. Returns the new state, or empty when the settings did not change.
     */
    public synchronized Optional<RuntimeState> install(WorldArchiveConfig config) {
        if (closed) {
            throw new IllegalStateException("WorldArchive is shutting down");
        }
        WorldArchiveConfig resolved = defaults.resolve(Objects.requireNonNull(config, "config"));
        if (current != null && current.config().equals(resolved)) {
            return Optional.empty();
        }
        RuntimeState state = build(resolved);
        current = state;
        open.add(state);
        return Optional.of(state);
    }

    /**
     * Readies a state that {@link #install} just built. The Git tools are checked again unless the
     * previous state checked them with the same Git settings, and the catalog learns the backups
     * the folders hold only when the folders changed, such as at the first load. The catalog scan
     * holds a work permit, so a settings save cannot move the folders while it runs.
     */
    public CompletionStage<Void> prime(Optional<RuntimeState> previous, RuntimeState state) {
        CompletableFuture<Void> tools = previous
                .filter(before -> before.config().git().equals(state.config().git())
                        && before.selector().gitTools() != RuntimeDestinationSelector.GitTools.UNCHECKED)
                .map(before -> {
                    state.selector().gitTools(before.selector().gitTools());
                    return CompletableFuture.<Void>completedFuture(null);
                })
                .orElseGet(() -> checkGitTools(state).toCompletableFuture());
        if (previous.filter(before -> before.storagePaths().equals(state.storagePaths())).isPresent()) {
            return tools;
        }
        ConfigurationGate.Permit permit = gate.enterSettingsWork();
        CompletableFuture<Void> scan = state.imports().rebuildLocal().handle((summary, failure) -> {
            if (failure != null) {
                LOGGER.warn("WorldArchive could not list the backups in its folders: {}",
                        SafeText.from(failure, "the folders could not be read", MESSAGE_LIMIT));
            } else if (summary.conflicts() > 0 || summary.issues() > 0) {
                LOGGER.warn("WorldArchive listed the backups in its folders with problems: {}", summary.message());
            }
            return (Void) null;
        }).toCompletableFuture();
        scan.whenComplete((ignored, failure) -> permit.close());
        return CompletableFuture.allOf(tools, scan);
    }

    /** Checks Git and Git LFS for a state, so its backups use Git only when both work. */
    public CompletionStage<Void> checkGitTools(RuntimeState state) {
        if (!state.config().git().enabled()) {
            state.selector().gitTools(RuntimeDestinationSelector.GitTools.TURNED_OFF);
            return CompletableFuture.completedFuture(null);
        }
        return state.git().probeTools().handle((health, failure) -> {
            if (failure != null) {
                LOGGER.warn("WorldArchive could not check Git: {}",
                        SafeText.from(failure, "the check failed", MESSAGE_LIMIT));
                state.selector().gitTools(RuntimeDestinationSelector.GitTools.CHECK_FAILED);
            } else {
                state.selector().gitTools(health.available()
                        ? RuntimeDestinationSelector.GitTools.AVAILABLE
                        : RuntimeDestinationSelector.GitTools.MISSING);
            }
            return null;
        });
    }

    /**
     * Admits a settings change before it is saved. A change that moves a backup folder takes the
     * folder-change permit, which is refused while backup work runs, and is refused when the
     * catalog lists backups in a folder that would move. The returned release runs once the change
     * was saved and published.
     *
     * @throws IllegalStateException when backup work runs or the catalog cannot be read
     * @throws IllegalArgumentException when the change would move a folder away from its backups
     */
    public Runnable admitSettingsChange(WorldArchiveConfig config) {
        Optional<RuntimeState> state = current();
        if (state.isEmpty()) {
            return () -> { };
        }
        RuntimeStoragePaths before = state.get().storagePaths();
        RuntimeStoragePaths after = RuntimeStoragePaths.from(defaults.resolve(config));
        if (before.equals(after)) {
            return () -> { };
        }
        ConfigurationGate.Permit permit = gate.enterFolderChange();
        try {
            RuntimeDestinationPathGuard.requireAllowed(before, after, catalog.listAll());
            return permit::close;
        } catch (IOException failure) {
            permit.close();
            throw new IllegalStateException("The backup folders cannot change now, because the backup list could"
                    + " not be read: " + SafeText.from(failure, "no reason was given", MESSAGE_LIMIT), failure);
        } catch (RuntimeException failure) {
            permit.close();
            throw failure;
        }
    }

    /** True while a backup of the world captures, waits or writes on any state. */
    public boolean busy(WorldId worldId) {
        return openStates().stream().anyMatch(state -> state.coordinator().isBusy(worldId));
    }

    /** Stops routing work and releases every state; work already running is not stopped here. */
    public void close() {
        List<RuntimeState> closing;
        synchronized (this) {
            closed = true;
            current = null;
            closing = List.copyOf(open);
            open.clear();
        }
        closing.forEach(ServiceGraph::closeQuietly);
    }

    public ConfigurationGate gate() {
        return gate;
    }

    public FileBackupCatalog catalog() {
        return catalog;
    }

    public WorldIdentityStore identities() {
        return identities;
    }

    public FileSystemBackupCaptureFactory captures() {
        return captures;
    }

    public ExecutorService executor() {
        return executor;
    }

    private synchronized List<RuntimeState> openStates() {
        return List.copyOf(open);
    }

    /** Closes the states that no work can use any more: every state but the current one. */
    private void closeRetiredStates() {
        List<RuntimeState> retired;
        synchronized (this) {
            retired = open.stream().filter(state -> state != current).toList();
            open.removeAll(retired);
        }
        retired.forEach(ServiceGraph::closeQuietly);
    }

    private static void closeQuietly(RuntimeState state) {
        try {
            state.close();
        } catch (RuntimeException exception) {
            LOGGER.warn("WorldArchive could not release old services: {}",
                    SafeText.from(exception, "they could not be closed", MESSAGE_LIMIT));
        }
    }

    /**
     * Whether a backup folder is one of WorldArchive's default folders, which a backup creates
     * when needed, or one the player chose; a folder whose place cannot be read counts as chosen.
     */
    private FolderOrigin origin(Path folder) {
        try {
            return defaults.origin(folder);
        } catch (IOException unreadable) {
            return FolderOrigin.CHOSEN;
        }
    }

    private RuntimeState build(WorldArchiveConfig config) {
        RuntimeStoragePaths paths = RuntimeStoragePaths.from(config);
        WorldGitSnapshotStore git = new WorldGitSnapshotStore(
                GitBackendSettings.from(config.git(), origin(paths.gitRepository())),
                config.worlds().stream()
                        .filter(world -> world.remoteUrl().isPresent())
                        .collect(Collectors.toUnmodifiableMap(WorldConfig::worldId, world -> world.remoteUrl().get())),
                new SystemGitCommandRunner(),
                executor);
        ZipBackupStoreResolver zipStores = new RuntimeZipBackupStores(paths, this::origin);
        RuntimeDestinationSelector selector = new RuntimeDestinationSelector(new ConfiguredBackupDestinationSelector(
                () -> config, List.of(git, new ZipBackupBackend(zipStores))));
        Set<WorldId> configuredWorlds = config.worlds().stream()
                .map(WorldConfig::worldId)
                .collect(Collectors.toUnmodifiableSet());
        return new RuntimeState(
                config,
                paths,
                git,
                selector,
                new SerializedBackupCoordinator(
                        catalog, captures, inventories, selector, captureMutex, operationGate, executor, clock),
                new BackupRecoveryService(
                        catalog, git, zipStores, importSources, deletions, identities, finalizer, executor,
                        operationGate, clock),
                new FileBackupImportService(
                        catalog, importSources, deletions, git, zipStores, () -> configuredWorlds, operationGate,
                        executor, clock),
                new ManagedStorageService(
                        () -> config, catalog, deletions, git, zipStores, history, operationGate, executor, clock,
                        clock.getZone()));
    }
}
