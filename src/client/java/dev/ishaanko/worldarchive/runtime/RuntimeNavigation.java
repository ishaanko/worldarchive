package dev.ishaanko.worldarchive.runtime;

import com.mojang.blaze3d.Blaze3D;
import dev.ishaanko.worldarchive.config.PathSafety;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.settings.ClientSettingsAccess;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import dev.ishaanko.worldarchive.ui.BackupBrowserScreen;
import dev.ishaanko.worldarchive.ui.BackupClientFacade;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.RestoreChoice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opens WorldArchive's screens, the backup folders, and a restored world. */
final class RuntimeNavigation {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final Minecraft minecraft;

    private final StateCalls calls;

    private final ServiceGraph graph;

    RuntimeNavigation(Minecraft minecraft, StateCalls calls, ServiceGraph graph) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.calls = Objects.requireNonNull(calls, "calls");
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** Opens the browser of {@code world} over {@code returnTo} on the render thread. */
    void openBrowser(Screen returnTo, BackupWorldContext world, BackupClientFacade facade) {
        minecraft.execute(() -> minecraft.setScreenAndShow(new BackupBrowserScreen(returnTo, world, facade)));
    }

    /**
     * Opens the folder that holds the selected backup, or the world's backups. A folder that does
     * not exist yet is never created here: its nearest existing parent opens instead, because an
     * empty folder in the place of a Git repository would break the world's next Git backup.
     */
    CompletionStage<Void> openManagedFolder(BackupWorldContext world, Optional<BackupId> selected) {
        return calls.withState(state -> AsyncTasks.supplyChecked(graph.executor(), () -> {
            Path folder = PathSafety.nearestExisting(folder(state, world, selected)).orElseThrow(() ->
                    new IOException("The backup folder is on a drive that is not connected"));
            minecraft.execute(() -> Blaze3D.openPath(folder));
            return null;
        }));
    }

    void openSettings(Screen returnTo) {
        minecraft.execute(() -> minecraft.setScreenAndShow(ClientSettingsAccess.createScreen(returnTo)));
    }

    void selectRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        transition(returnTo, result, RestoreChoice.SELECT);
    }

    void playRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        transition(returnTo, result, RestoreChoice.PLAY);
    }

    private Path folder(RuntimeState state, BackupWorldContext world, Optional<BackupId> selected) throws IOException {
        Path zip = state.storagePaths().zipDirectory(world.worldId()).resolve(world.worldId().toString());
        Path git = state.git().repositoryFor(world.worldId());
        if (selected.isPresent()) {
            Optional<BackupRecord> record = graph.catalog().find(selected.get());
            if (record.map(found -> holds(found, DestinationType.ZIP)).orElse(false)) {
                return zip;
            }
            if (record.map(found -> holds(found, DestinationType.GIT)).orElse(false)) {
                return git;
            }
        }
        return state.config().zip().enabled() ? zip : git;
    }

    private static boolean holds(BackupRecord record, DestinationType type) {
        return record.result().destinations().stream()
                .anyMatch(destination -> destination.destination() == type && destination.isDurable());
    }

    /**
     * Shows or plays a restored world. A world open in the game is left first. An Edit World
     * screen on the way back is closed through its own callback, which releases the lock it holds
     * on the edited world; otherwise that world would drop out of the world list.
     */
    private void transition(Screen returnTo, RestoreBackupResult result, RestoreChoice choice) {
        Objects.requireNonNull(returnTo, "returnTo");
        Objects.requireNonNull(result, "result");
        minecraft.execute(() -> {
            Optional<String> storageName = restoredStorageName(result);
            if (storageName.isEmpty()) {
                return;
            }
            Screen origin = returnTo;
            if (origin instanceof EditWorldScreen editWorld) {
                editWorld.onClose();
                origin = minecraft.gui.screen();
            }
            if (minecraft.hasSingleplayerServer() || minecraft.level != null || minecraft.getConnection() != null) {
                minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                origin = new TitleScreen();
            }
            switch (choice) {
                case PLAY -> play(origin, storageName.get());
                case SELECT -> showInWorldList(origin, storageName.get());
                default -> throw new IllegalArgumentException("Unknown restore choice " + choice);
            }
        });
    }

    private void showInWorldList(Screen returnTo, String storageName) {
        SelectWorldScreen screen = new SelectWorldScreen(returnTo);
        minecraft.setScreenAndShow(screen);
        RestoredWorldSelection.install(screen, storageName);
    }

    /** Opens the restored world; when it cannot open, the world list shows it selected. */
    private void play(Screen returnTo, String storageName) {
        try (LevelStorageSource.LevelStorageAccess ignored =
                minecraft.getLevelSource().validateAndCreateAccess(storageName)) {
            // Validation and a clean session close happen before vanilla starts the world.
        } catch (IOException | ContentValidationException exception) {
            LOGGER.warn("The restored world could not be opened: {}", SafeText.from(exception, "no reason", 300));
            showInWorldList(returnTo, storageName);
            return;
        }
        minecraft.createWorldOpenFlows().openWorld(storageName, () -> showInWorldList(returnTo, storageName));
    }

    /** The restored world's folder name in the game's saves folder, compared by real paths. */
    private Optional<String> restoredStorageName(RestoreBackupResult result) {
        try {
            Path restored = result.restoredWorldDirectory().toRealPath();
            Path saves = minecraft.getLevelSource().getBaseDir().toRealPath();
            Path name = restored.getFileName();
            if (name != null && saves.equals(restored.getParent())
                    && Files.isDirectory(restored, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(name.toString());
            }
            LOGGER.warn("The restored world {} is not in the saves folder {}", restored, saves);
        } catch (IOException | SecurityException exception) {
            LOGGER.warn("The restored world could not be found: {}", SafeText.from(exception, "no reason", 300));
        }
        return Optional.empty();
    }
}
