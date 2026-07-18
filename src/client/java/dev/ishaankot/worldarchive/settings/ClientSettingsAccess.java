package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.WorldArchiveMetadata;
import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldArchiveConfigStore;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    private static volatile WorldArchiveSettingsRepository repository;

    private static volatile SettingsDefaults settingsDefaults;

    private static volatile SettingsHealthProbe healthProbe;

    private static volatile WorldArchiveConfig fallbackConfig = WorldArchiveConfig.defaults();

    private static volatile CompletableFuture<Void> initialization =
            CompletableFuture.completedFuture(null);

    private ClientSettingsAccess() {
    }

    public static synchronized void initialize() {
        if (repository != null || settingsDefaults != null) {
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
            try {
                currentRepository.save(config);
                STATUS.set("Settings saved");
                return currentRepository.current();
            } catch (IOException exception) {
                STATUS.set(safeMessage(exception, "Settings could not be saved"));
                throw new SettingsSaveException("Settings could not be saved", exception);
            }
        }, SETTINGS_EXECUTOR);
    }

    public static CompletionStage<SettingsValidation> validate(
            SettingsDraft draft,
            List<Path> knownWorldPaths) {
        Objects.requireNonNull(draft, "draft");
        List<Path> worlds = List.copyOf(Objects.requireNonNull(knownWorldPaths, "knownWorldPaths"));
        return CompletableFuture.supplyAsync(() -> draft.validate(worlds), SETTINGS_EXECUTOR);
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

    public static List<Path> knownWorldPaths() {
        initialize();
        return KNOWN_WORLD_PATHS.get();
    }

    public static String status() {
        return STATUS.get();
    }

    private static void loadAndDiscoverWorlds(Path savesDirectory) {
        WorldArchiveSettingsRepository currentRepository = repository;
        if (currentRepository == null) {
            return;
        }
        try {
            List<Path> paths = WorldFolderDiscovery.discover(savesDirectory);
            KNOWN_WORLD_PATHS.set(paths);
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
