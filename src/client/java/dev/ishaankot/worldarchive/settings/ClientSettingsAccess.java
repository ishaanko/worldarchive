package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.WorldArchiveMetadata;
import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldArchiveConfigStore;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-wide settings gateway shared by Mod Menu, commands, and world screens. */
public final class ClientSettingsAccess {
    private static final long HEALTH_DEBOUNCE_MILLIS = 250L;

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldArchiveMetadata.MOD_NAME);

    private static final ExecutorService SETTINGS_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WorldArchive settings");
        thread.setDaemon(true);
        return thread;
    });

    private static final ScheduledExecutorService HEALTH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "WorldArchive settings health");
                thread.setDaemon(true);
                return thread;
            });

    private static final ExecutorService PICKER_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WorldArchive folder picker");
        thread.setDaemon(true);
        return thread;
    });

    private static final NativeFolderChooser FOLDER_CHOOSER =
            new TinyFileDialogsFolderChooser(PICKER_EXECUTOR);

    private static final AtomicReference<List<Path>> KNOWN_WORLD_PATHS =
            new AtomicReference<>(List.of());

    private static final AtomicReference<String> STATUS =
            new AtomicReference<>("Loading settings...");

    private static final CopyOnWriteArrayList<Consumer<WorldArchiveConfig>> CONFIGURATION_LISTENERS =
            new CopyOnWriteArrayList<>();

    private static final CopyOnWriteArrayList<Function<WorldArchiveConfig, Runnable>>
            CONFIGURATION_GUARDS =
            new CopyOnWriteArrayList<>();

    private static final AtomicBoolean SHUT_DOWN = new AtomicBoolean();

    private static volatile WorldArchiveSettingsRepository repository;

    private static volatile SettingsDefaults settingsDefaults;

    private static volatile SettingsHealthProbe healthProbe;

    private static volatile Path worldsDirectory;

    private static volatile WorldArchiveConfig fallbackConfig = WorldArchiveConfig.defaults();

    private static volatile CompletableFuture<Void> initialization =
            CompletableFuture.completedFuture(null);

    private ClientSettingsAccess() {
    }

    public static synchronized void initialize() {
        if (SHUT_DOWN.get() || repository != null || settingsDefaults != null) {
            return;
        }
        Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath()
                .toAbsolutePath()
                .normalize();
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("worldarchive.json");
        settingsDefaults = SettingsDefaults.fromConfigFile(configFile);
        fallbackConfig = settingsDefaults.resolve(WorldArchiveConfig.defaults());
        healthProbe = new SystemSettingsHealthProbe(gameDirectory);
        try {
            repository = new WorldArchiveSettingsRepository(
                    new WorldArchiveConfigStore(configFile),
                    KNOWN_WORLD_PATHS::get,
                    settingsDefaults);
        } catch (IOException exception) {
            STATUS.set(safeMessage(exception, "Settings storage is unavailable"));
            LOGGER.error("WorldArchive settings storage is unavailable", exception);
            return;
        }

        Path savesDirectory = gameDirectory.resolve("saves");
        worldsDirectory = savesDirectory;
        initialization = CompletableFuture.runAsync(
                () -> loadAndDiscoverWorlds(savesDirectory),
                SETTINGS_EXECUTOR);
    }

    public static WorldArchiveConfig snapshot() {
        initialize();
        WorldArchiveSettingsRepository currentRepository = repository;
        return currentRepository == null ? fallbackConfig : currentRepository.current();
    }

    public static CompletionStage<Void> ready() {
        initialize();
        return initialization;
    }

    public static boolean isLoading() {
        initialize();
        return !initialization.isDone();
    }

    public static CompletionStage<WorldArchiveConfig> save(WorldArchiveConfig config) {
        Objects.requireNonNull(config, "config");
        initialize();
        WorldArchiveSettingsRepository currentRepository = repository;
        if (currentRepository == null) {
            return CompletableFuture.failedFuture(new IOException("Settings storage is unavailable"));
        }
        STATUS.set("Saving settings...");
        return initialization.thenApplyAsync(ignored -> {
            List<Runnable> releases = List.of();
            try {
                releases = acquireConfigurationGuards(config);
                WorldArchiveConfig refreshed = refreshWorlds(config);
                currentRepository.save(refreshed);
                WorldArchiveConfig saved = currentRepository.current();
                STATUS.set("Settings saved");
                publishConfiguration(saved);
                return saved;
            } catch (IOException exception) {
                STATUS.set(safeMessage(exception, "Settings could not be saved"));
                throw new SettingsSaveException("Settings could not be saved", exception);
            } catch (RuntimeException exception) {
                STATUS.set(safeMessage(exception, "Settings could not be saved"));
                throw exception;
            } finally {
                releaseConfigurationGuards(releases);
            }
        }, SETTINGS_EXECUTOR);
    }

    public static CompletionStage<SettingsValidation> validate(
            SettingsDraft draft,
            List<Path> knownWorldPaths) {
        Objects.requireNonNull(draft, "draft");
        List<Path> worlds = List.copyOf(Objects.requireNonNull(knownWorldPaths, "knownWorldPaths"));
        return CompletableFuture.supplyAsync(
                () -> draft.validate(mergedWorldPaths(worlds, KNOWN_WORLD_PATHS.get())),
                SETTINGS_EXECUTOR);
    }

    public static CancellableRequest<SettingsHealthSnapshot> probeHealth(
            SettingsProbeRequest request) {
        Objects.requireNonNull(request, "request");
        initialize();
        SettingsHealthProbe currentProbe = healthProbe;
        if (currentProbe == null) {
            return completedRequest(SettingsHealthSnapshot.unavailable(
                    request,
                    "Destination health checks are unavailable"));
        }

        CompletableFuture<SettingsHealthSnapshot> completion = new CompletableFuture<>();
        Future<?> task = HEALTH_EXECUTOR.schedule(() -> {
            try {
                completion.complete(currentProbe.probe(request));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                completion.cancel(false);
            } catch (RuntimeException exception) {
                completion.completeExceptionally(exception);
            }
        }, HEALTH_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        return new CancellableRequest<>(completion, () -> {
            task.cancel(true);
            completion.cancel(false);
        });
    }

    public static SettingsDraft defaultsKeepingWorlds(WorldArchiveConfig current) {
        Objects.requireNonNull(current, "current");
        initialize();
        SettingsDefaults currentDefaults = settingsDefaults;
        return currentDefaults == null
                ? SettingsDraft.defaultsKeepingWorlds(current)
                : SettingsDraft.defaultsKeepingWorlds(current, currentDefaults);
    }

    public static Screen createScreen(Screen parent) {
        initialize();
        return new WorldArchiveSettingsScreen(parent, FOLDER_CHOOSER);
    }

    public static CancellableRequest<FolderSelectionResult> chooseFolder(
            String title,
            Optional<Path> initialDirectory) {
        initialize();
        return FOLDER_CHOOSER.chooseFolder(
                Objects.requireNonNull(title, "title"),
                Objects.requireNonNull(initialDirectory, "initialDirectory"));
    }

    /** Applies imported remotes only to already configured live worlds. */
    public static CompletionStage<WorldArchiveConfig> connectWorldRemotes(
            Map<WorldId, String> connections) {
        Map<WorldId, String> requested = Map.copyOf(
                Objects.requireNonNull(connections, "connections"));
        if (requested.isEmpty()) {
            return CompletableFuture.completedFuture(snapshot());
        }
        return ready().thenCompose(ignored -> {
            WorldArchiveConfig current = snapshot();
            List<WorldConfig> worlds = current.worlds().stream()
                    .map(world -> requested.containsKey(world.worldId())
                            ? new WorldConfig(
                                    world.worldId(),
                                    world.enabled(),
                                    world.path(),
                                    Optional.of(requested.get(world.worldId())),
                                    world.zipDestination())
                            : world)
                    .toList();
            WorldArchiveConfig connected = new WorldArchiveConfig(
                    WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                    current.triggers(),
                    current.git(),
                    current.zip(),
                    worlds);
            return connected.equals(current)
                    ? CompletableFuture.completedFuture(current)
                    : save(connected);
        });
    }

    public static List<Path> knownWorldPaths() {
        initialize();
        return KNOWN_WORLD_PATHS.get();
    }

    /** Immediately remembers a runtime world path and asynchronously persists its identity. */
    public static CompletionStage<WorldArchiveConfig> registerWorld(
            WorldId worldId,
            Path worldDirectory) {
        Objects.requireNonNull(worldId, "worldId");
        Path world = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        initialize();
        rememberWorldPaths(List.of(world));
        WorldArchiveSettingsRepository currentRepository = repository;
        if (currentRepository == null) {
            return CompletableFuture.failedFuture(
                    new IOException("Settings storage is unavailable"));
        }
        try {
            currentRepository.current().validateDestinations(KNOWN_WORLD_PATHS.get());
        } catch (IOException exception) {
            STATUS.set(safeMessage(
                    exception, "A destination overlaps the newly registered world"));
            throw new IllegalArgumentException(
                    safeMessage(exception, "A destination overlaps the newly registered world"),
                    exception);
        }
        return initialization.thenApplyAsync(ignored -> {
            try {
                WorldArchiveConfig current = currentRepository.current();
                WorldArchiveConfig candidate = upsertWorld(current, worldId, world);
                WorldArchiveConfig refreshed = refreshWorlds(candidate);
                if (!refreshed.equals(current)) {
                    List<Runnable> releases = acquireConfigurationGuards(refreshed);
                    try {
                        currentRepository.save(refreshed);
                        publishConfiguration(currentRepository.current());
                    } finally {
                        releaseConfigurationGuards(releases);
                    }
                }
                return currentRepository.current();
            } catch (IOException exception) {
                STATUS.set(safeMessage(exception, "World settings could not be saved"));
                throw new CompletionException(exception);
            }
        }, SETTINGS_EXECUTOR);
    }

    public static String status() {
        return STATUS.get();
    }

    /** Registers a process-lifetime listener for successfully loaded or saved configurations. */
    public static void addConfigurationListener(Consumer<WorldArchiveConfig> listener) {
        CONFIGURATION_LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Registers a save guard held from pre-persistence validation through publication. */
    public static void addConfigurationGuard(Function<WorldArchiveConfig, Runnable> guard) {
        CONFIGURATION_GUARDS.add(Objects.requireNonNull(guard, "guard"));
    }

    /** Stops client-owned settings, health, and folder-picker workers. */
    public static void shutdown() {
        if (!SHUT_DOWN.compareAndSet(false, true)) {
            return;
        }
        HEALTH_EXECUTOR.shutdownNow();
        PICKER_EXECUTOR.shutdownNow();
        SETTINGS_EXECUTOR.shutdown();
        boolean interrupted = false;
        try {
            if (!SETTINGS_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SETTINGS_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            SETTINGS_EXECUTOR.shutdownNow();
        } finally {
            CONFIGURATION_GUARDS.clear();
            CONFIGURATION_LISTENERS.clear();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void loadAndDiscoverWorlds(Path savesDirectory) {
        WorldArchiveSettingsRepository currentRepository = repository;
        if (currentRepository == null) {
            return;
        }
        try {
            List<Path> paths = WorldFolderDiscovery.discover(savesDirectory);
            rememberWorldPaths(paths);
            WorldArchiveConfig loaded = currentRepository.load();
            List<DiscoveredWorld> discovered = discoverWorldIdentities(paths);
            WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                    loaded.worlds(),
                    discovered);
            WorldArchiveConfig reconciled = new WorldArchiveConfig(
                    WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                    loaded.triggers(),
                    loaded.git(),
                    loaded.zip(),
                    reconciliation.worlds());
            if (!reconciled.equals(loaded)) {
                currentRepository.save(reconciled);
            }
            if (reconciliation.errors().isEmpty()) {
                STATUS.set("Settings loaded");
            } else {
                STATUS.set("Settings loaded; " + reconciliation.errors().size()
                        + " world folder(s) need attention");
                reconciliation.errors().forEach(error -> LOGGER.warn("World discovery: {}", error));
            }
            publishConfiguration(currentRepository.current());
        } catch (IOException exception) {
            STATUS.set(safeMessage(exception, "Settings could not be loaded; using defaults"));
            LOGGER.error("WorldArchive settings could not be loaded", exception);
        }
    }

    private static List<DiscoveredWorld> discoverWorldIdentities(List<Path> paths) {
        WorldIdentityStore identityStore = new WorldIdentityStore();
        List<DiscoveredWorld> discovered = new ArrayList<>(paths.size());
        for (Path path : paths) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("World identity discovery was cancelled");
            }
            try {
                discovered.add(DiscoveredWorld.success(path, identityStore.loadOrCreate(path)));
            } catch (IOException exception) {
                discovered.add(DiscoveredWorld.failure(
                        path,
                        safeMessage(exception, "A world identity could not be loaded")));
            }
        }
        return List.copyOf(discovered);
    }

    private static WorldArchiveConfig refreshWorlds(WorldArchiveConfig config) throws IOException {
        Path savesDirectory = worldsDirectory;
        if (savesDirectory == null) {
            return config;
        }
        return refreshWorlds(config, savesDirectory, ClientSettingsAccess::rememberWorldPaths);
    }

    static WorldArchiveConfig refreshWorlds(
            WorldArchiveConfig config,
            Path savesDirectory,
            Consumer<List<Path>> pathConsumer) throws IOException {
        List<Path> paths = WorldFolderDiscovery.discover(savesDirectory);
        pathConsumer.accept(paths);
        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                config.worlds(), discoverWorldIdentities(paths));
        reconciliation.errors().forEach(error -> LOGGER.warn("World discovery: {}", error));
        return withWorlds(config, reconciliation.worlds());
    }

    static WorldArchiveConfig upsertWorld(
            WorldArchiveConfig config,
            WorldId worldId,
            Path worldDirectory) {
        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                config.worlds(),
                List.of(DiscoveredWorld.success(worldDirectory, worldId)));
        return withWorlds(config, reconciliation.worlds());
    }

    static List<Path> mergedWorldPaths(List<Path> existing, List<Path> additions) {
        List<Path> merged = new ArrayList<>(existing.size() + additions.size());
        for (Path path : existing) {
            Path normalized = Objects.requireNonNull(path, "knownWorldPath")
                    .toAbsolutePath()
                    .normalize();
            if (!merged.contains(normalized)) {
                merged.add(normalized);
            }
        }
        for (Path path : additions) {
            Path normalized = Objects.requireNonNull(path, "knownWorldPath")
                    .toAbsolutePath()
                    .normalize();
            if (!merged.contains(normalized)) {
                merged.add(normalized);
            }
        }
        return List.copyOf(merged);
    }

    private static void rememberWorldPaths(List<Path> paths) {
        KNOWN_WORLD_PATHS.updateAndGet(existing -> mergedWorldPaths(existing, paths));
    }

    private static WorldArchiveConfig withWorlds(
            WorldArchiveConfig config,
            List<WorldConfig> worlds) {
        return new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                config.triggers(),
                config.git(),
                config.zip(),
                worlds);
    }

    private static void publishConfiguration(WorldArchiveConfig config) {
        for (Consumer<WorldArchiveConfig> listener : CONFIGURATION_LISTENERS) {
            try {
                listener.accept(config);
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "WorldArchive configuration listener failed: {}",
                        safeMessage(exception, "Configuration listener failed"));
            }
        }
    }

    private static List<Runnable> acquireConfigurationGuards(WorldArchiveConfig config) {
        List<Runnable> releases = new ArrayList<>(CONFIGURATION_GUARDS.size());
        try {
            for (Function<WorldArchiveConfig, Runnable> guard : CONFIGURATION_GUARDS) {
                releases.add(Objects.requireNonNull(
                        guard.apply(config),
                        "configuration guard release"));
            }
            return List.copyOf(releases);
        } catch (RuntimeException | Error exception) {
            releaseConfigurationGuards(releases);
            throw exception;
        }
    }

    private static void releaseConfigurationGuards(List<Runnable> releases) {
        for (int index = releases.size() - 1; index >= 0; index--) {
            releases.get(index).run();
        }
    }

    private static CancellableRequest<SettingsHealthSnapshot> completedRequest(
            SettingsHealthSnapshot value) {
        return new CancellableRequest<>(CompletableFuture.completedFuture(value), () -> { });
    }

    private static String safeMessage(Throwable throwable, String fallback) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String redacted = SensitiveDataRedactor.redact(message)
                .replaceAll("\\p{Cntrl}+", " ")
                .strip();
        if (redacted.isEmpty()) {
            return fallback;
        }
        return redacted.length() <= 512 ? redacted : redacted.substring(0, 512);
    }

    private static final class SettingsSaveException extends RuntimeException {
        private SettingsSaveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
