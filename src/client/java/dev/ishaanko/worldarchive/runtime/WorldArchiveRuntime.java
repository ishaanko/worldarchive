package dev.ishaanko.worldarchive.runtime;

import com.mojang.blaze3d.platform.SDLEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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

    /** How long work that quitting interrupted gets to stop its Git processes and remove its partial files. */
    private static final Duration CLEANUP_WAIT = Duration.ofSeconds(5);

    /** How long one frame of the quit screen waits for the work. */
    private static final Duration FRAME = Duration.ofMillis(16);

    private static WorldArchiveRuntime instance;

    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("worldarchive-worker-", 0).factory());

    private final AtomicBoolean closed = new AtomicBoolean();

    /** The worlds whose folder the settings could not record, with that folder. */
    private final Map<WorldId, Path> unsavedWorlds = new ConcurrentHashMap<>();

    private final Minecraft minecraft;

    private final ServiceGraph graph;

    private final WorldIdentityResolver resolver;

    private final RuntimeBackgroundBackupMonitor monitor;

    private final LiveWorldBackups live;

    private final RuntimeClientFacade facade;

    private WorldArchiveRuntime(Minecraft minecraft) {
        this.minecraft = minecraft;
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
     * destinations together; what is left after it is interrupted, and gets a short grace to
     * clean up. What could not finish is kept as a notice for the next start.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        live.close();
        try {
            boolean finished = minecraft.isSameThread()
                    ? awaitWorkShowingScreen()
                    : graph.gate().awaitIdle(QUIT_WAIT);
            if (!finished) {
                LOGGER.warn("WorldArchive work did not finish before the game closed; it was stopped");
                monitor.keep(live.exitBackupsRunning()
                        ? BackgroundNotices.exitInterrupted()
                        : BackgroundNotices.workInterrupted());
            }
            if (!graph.shutdown(finished, CLEANUP_WAIT)) {
                LOGGER.warn("WorldArchive work did not stop in time; the next start removes what it left");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            graph.close();
            workers.shutdownNow();
        }
        ClientSettingsAccess.shutdown();
    }

    /**
     * Waits for the work on the render thread, which happens when the game quits from the title
     * screen. A message screen is drawn and the window's events are handled meanwhile, so the
     * game does not stop answering; the toast of a world-exit backup still shows its progress.
     */
    private boolean awaitWorkShowingScreen() throws InterruptedException {
        ConfigurationGate gate = graph.gate();
        if (gate.awaitIdle(Duration.ZERO)) {
            return true;
        }
        long deadline = System.nanoTime() + QUIT_WAIT.toNanos();
        try {
            minecraft.gui.setScreen(new GenericMessageScreen(
                    Component.translatable("screen.worldarchive.runtime.quit_wait")));
            SDLEventHandler events = new SDLEventHandler(minecraft, minecraft.getWindow());
            while (!gate.awaitIdle(FRAME)) {
                if (System.nanoTime() - deadline >= 0) {
                    return false;
                }
                RenderSystem.pumpEvents(events);
                minecraft.renderFrame(false);
            }
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn("The quit screen could not be drawn: {}", SafeText.from(failure, "no reason", 300));
            return gate.awaitIdle(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        }
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
