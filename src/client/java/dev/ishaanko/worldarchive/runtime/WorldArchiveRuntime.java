package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.settings.ClientSettingsAccess;
import dev.ishaanko.worldarchive.settings.SettingsService;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import dev.ishaanko.worldarchive.ui.BackupClientFacade;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldArchive in the running game: it builds the backup engine for the game folder, keeps it in
 * step with the settings, connects it to the game's events and screens, and shuts it down when the
 * game quits.
 */
public final class WorldArchiveRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    /** How long quitting waits for backups, restores and deletes that still run, capture included. */
    private static final Duration QUIT_WAIT = Duration.ofSeconds(30);

    private static WorldArchiveRuntime instance;

    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("worldarchive-worker-", 0).factory());

    private final AtomicBoolean closed = new AtomicBoolean();

    /** The worlds whose folder the settings could not record, with that folder. */
    private final Map<WorldId, Path> unsavedWorlds = new ConcurrentHashMap<>();

    private final ServiceGraph graph;

    private final WorldIdentityResolver resolver;

    private final RuntimeBackgroundBackupMonitor monitor;

    private final LiveWorldBackups live;

    private final RuntimeClientFacade facade;

    private WorldArchiveRuntime(Minecraft minecraft) {
        Path game = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        Path storageRoot = game.resolve("worldarchive");
        Path saves = game.resolve("saves");
        graph = new ServiceGraph(
                storageRoot,
                new FileSystemBackupCaptureFactory(
                        storageRoot.resolve("capture-temp"), RunningGameVersion.current(), SourceCaptureObserver.NONE),
                new MinecraftRestoredWorldMetadataFinalizer(),
                workers,
                Clock.systemDefaultZone());
        resolver = new WorldIdentityResolver(saves, graph.identities(), new SettingsRecorder());
        monitor = new RuntimeBackgroundBackupMonitor(
                minecraft, new RuntimeNoticeStore(storageRoot.resolve("last-background-warning.txt")), closed::get);
        live = new LiveWorldBackups(graph, resolver, monitor, System::nanoTime);
        StateCalls calls = new StateCalls(graph, closed::get);
        facade = new RuntimeClientFacade(
                graph,
                resolver,
                live,
                calls,
                new RuntimeNavigation(minecraft, calls, graph),
                saves,
                this::unsavedWorldWarning);
    }

    /** Starts the runtime once; the settings finish loading in the background. */
    public static synchronized WorldArchiveRuntime initialize() {
        if (instance == null) {
            instance = new WorldArchiveRuntime(Minecraft.getInstance());
            instance.start();
        }
        return instance;
    }

    /** What the backup screens and the world list integrations call. */
    public BackupClientFacade facade() {
        return facade;
    }

    /** Opens the open world's backup browser over {@code returnTo}; false while its identity is still unknown. */
    public boolean openBrowser(Screen returnTo) {
        return facade.openLiveBrowser(returnTo);
    }

    private void start() {
        new FabricLifecycle(live, this::shutdown).register();
        monitor.register();
        // A crash can leave world copies behind; removing them may take a while, so never on the render thread.
        AsyncTasks.run(workers, () -> {
            try {
                graph.captures().removeAbandonedCaptures();
            } catch (IOException failure) {
                LOGGER.warn("Old world copies could not be removed: {}", SafeText.from(failure, "no reason", 300));
            }
        });
        SettingsService settings = ClientSettingsAccess.service();
        settings.addConfigurationGuard(graph::admitSettingsChange);
        settings.addConfigurationListener(this::reload);
        ClientSettingsAccess.ready().whenComplete((config, failure) -> {
            if (failure == null) {
                reload(config);
            } else {
                // Backups wait; a fixed file or a reset publishes the settings to reload.
                LOGGER.warn("WorldArchive settings could not be read, so backups are paused: {}",
                        SafeText.from(failure, "no reason was given", 300));
            }
        });
    }

    /** Installs the services for new settings; runs on the settings thread, and again for the same settings does nothing. */
    private synchronized void reload(WorldArchiveConfig config) {
        if (closed.get()) {
            return;
        }
        Optional<RuntimeState> previous = graph.current();
        Optional<RuntimeState> installed = graph.install(config);
        installed.ifPresent(state -> {
            resolver.configure(state.config());
            live.reconcile();
            graph.prime(previous, state);
        });
        graph.current().ifPresent(state -> state.config().worlds().forEach(
                world -> unsavedWorlds.remove(world.worldId(), world.path())));
    }

    private Optional<String> unsavedWorldWarning(WorldId worldId) {
        return unsavedWorlds.containsKey(worldId)
                ? Optional.of(StateCalls.text("screen.worldarchive.runtime.world_not_saved"))
                : Optional.empty();
    }

    /**
     * Stops at quit. New work is refused, and work that still runs gets one wait, capture and
     * destinations together. What could not finish is kept as a notice for the next start.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        live.close();
        boolean finished;
        try {
            finished = graph.gate().awaitIdle(QUIT_WAIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            LOGGER.warn("WorldArchive work did not finish before the game closed");
            monitor.keep(live.exitBackupsRunning()
                    ? BackgroundNotices.exitInterrupted()
                    : BackgroundNotices.workInterrupted());
        }
        graph.close();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        ClientSettingsAccess.shutdown();
    }

    /** Tells the settings about the worlds the resolver meets. */
    private final class SettingsRecorder implements WorldIdentityResolver.Listener {
        @Override
        public void found(WorldId worldId, Path worldDirectory) {
            ClientSettingsAccess.service().registerWorld(worldId, worldDirectory).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    unsavedWorlds.remove(worldId, worldDirectory);
                } else {
                    unsavedWorlds.put(worldId, worldDirectory);
                    LOGGER.warn("The settings could not record the folder of a world: {}",
                            SafeText.from(failure, "no reason was given", 300));
                }
            });
        }

        @Override
        public void copyFound(Path copy) {
            // The settings scan gives a copied world its own identity; the next resolution then succeeds.
            ClientSettingsAccess.service().saveSettings(UnaryOperator.identity()).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn("The saves folder could not be scanned for copied worlds: {}",
                            SafeText.from(failure, "no reason was given", 300));
                }
            });
        }
    }
}
