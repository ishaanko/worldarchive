package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.DefaultDestinations;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfigStore;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.storage.git.SystemGitCommandRunner;
import dev.ishaanko.worldarchive.ui.model.FolderSelectionResult;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Minecraft glue for WorldArchive settings: builds the one {@link SettingsService} for the game
 * folder and runs its work on a settings thread, runs health probes on their own thread, shows
 * the SDL folder picker, and opens the settings screen.
 */
public final class ClientSettingsAccess {
    private static final long HEALTH_DEBOUNCE_MILLIS = 250L;

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

    private static final SdlFolderChooser FOLDER_CHOOSER = new SdlFolderChooser(
            task -> Minecraft.getInstance().schedule(task),
            () -> Minecraft.getInstance().getWindow().handle());

    private static final AtomicBoolean SHUT_DOWN = new AtomicBoolean();

    private static volatile SettingsService service;

    private static volatile Path gameDirectory;

    private static volatile CompletionStage<WorldArchiveConfig> ready;

    private ClientSettingsAccess() {
    }

    /** Builds the settings service and starts loading the settings; later calls do nothing. */
    public static synchronized void initialize() {
        if (service != null) {
            return;
        }
        Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("worldarchive.json");
        gameDirectory = game;
        service = new SettingsService(
                new WorldArchiveConfigStore(configFile, new DefaultDestinations(game.resolve("worldarchive"))),
                game.resolve("saves"),
                new WorldIdentityStore(),
                SETTINGS_EXECUTOR,
                Clock.systemUTC());
        ready = service.load();
    }

    /** The settings service of this game folder. */
    public static SettingsService service() {
        initialize();
        return service;
    }

    /**
     * Completes when the settings first loaded. Fails with
     * {@link dev.ishaanko.worldarchive.config.UnreadableConfigurationException} when the file
     * cannot be read; backups then wait until the settings can be read or are reset.
     */
    public static CompletionStage<WorldArchiveConfig> ready() {
        initialize();
        return ready;
    }

    public static Screen createScreen(Screen parent) {
        initialize();
        return new WorldArchiveSettingsScreen(
                parent, service, new SettingsHealthProbe(gameDirectory, new SystemGitCommandRunner()));
    }

    /** Shows the platform folder picker. Cancelling the result only ignores the choice; the dialog stays open. */
    public static CompletableFuture<FolderSelectionResult> pickFolder(String title, Optional<Path> initialDirectory) {
        return FOLDER_CHOOSER.chooseFolder(
                Objects.requireNonNull(title, "title"),
                Objects.requireNonNull(initialDirectory, "initialDirectory"));
    }

    /**
     * Runs a probe after a short pause, so a burst of edits starts one probe. Cancelling the
     * result stops the probe, interrupting it when it already runs. Every failure completes the
     * result, so the footer never waits forever.
     */
    static CompletableFuture<SettingsHealthSnapshot> probeHealth(
            SettingsHealthProbe probe,
            SettingsProbeRequest request) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(request, "request");
        CompletableFuture<SettingsHealthSnapshot> result = new CompletableFuture<>();
        ScheduledFuture<?> task = HEALTH_EXECUTOR.schedule(() -> {
            try {
                result.complete(probe.probe(request));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                result.cancel(false);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, HEALTH_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                task.cancel(true);
            }
        });
        return result;
    }

    /** Stops the settings and health threads, letting saving settings finish for a few seconds. */
    public static void shutdown() {
        if (!SHUT_DOWN.compareAndSet(false, true)) {
            return;
        }
        HEALTH_EXECUTOR.shutdownNow();
        SETTINGS_EXECUTOR.shutdown();
        try {
            if (!SETTINGS_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SETTINGS_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException exception) {
            SETTINGS_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
