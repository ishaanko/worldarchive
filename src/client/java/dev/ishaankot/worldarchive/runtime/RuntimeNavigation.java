package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import dev.ishaankot.worldarchive.ui.BackupBrowserScreen;
import dev.ishaankot.worldarchive.ui.BackupWorldContext;
import dev.ishaankot.worldarchive.ui.BackupWorldSelection;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;

/** Owns client-screen transitions and managed-folder navigation. */
final class RuntimeNavigation {
    private final WorldArchiveRuntime runtime;

    RuntimeNavigation(WorldArchiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    void openManagedFolder(
            BackupWorldContext world,
            Optional<BackupRow> selectedBackup) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(selectedBackup, "selectedBackup");
        RuntimeConfigurationGate.Permit permit = runtime.configurationGate().enterBackup();
        boolean transferred = false;
        try {
            RuntimeState state = runtime.requireCurrentState();
            runtime.storageIssue(state).ifPresent(issue -> {
                throw new IllegalStateException(issue);
            });
            Path destination = managedPath(state, world, selectedBackup);
            runtime.workerExecutor().execute(
                    () -> openManagedFolder(destination, permit));
            transferred = true;
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("WorldArchive is shutting down");
        } finally {
            if (!transferred) {
                permit.close();
            }
        }
    }

    void openSettings(Screen returnTo) {
        Objects.requireNonNull(returnTo, "returnTo");
        if (!runtime.isClosed()) {
            Minecraft minecraft = runtime.minecraft();
            minecraft.execute(() -> minecraft.setScreenAndShow(
                    ClientSettingsAccess.createScreen(returnTo)));
        }
    }

    void selectRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        transitionToRestoredWorld(returnTo, result, false);
    }

    void playRestoredWorld(Screen returnTo, RestoreBackupResult result) {
        transitionToRestoredWorld(returnTo, result, true);
    }

    void openBrowser() {
        BackupWorldContext world = runtime.currentLiveWorld();
        if (world == null || runtime.unavailable()) {
            return;
        }
        Minecraft minecraft = runtime.minecraft();
        minecraft.execute(() -> {
            if (!runtime.isClosed()) {
                minecraft.setScreenAndShow(new BackupBrowserScreen(
                        new PauseScreen(true),
                        world,
                        runtime));
            }
        });
    }

    void openRestore(BackupId backupId) {
        openBrowserForBackup(backupId, CommandAction.RESTORE);
    }

    void openDeleteConfirmation(BackupId backupId) {
        openBrowserForBackup(backupId, CommandAction.DELETE);
    }

    boolean sourceDirectoryAvailable(BackupWorldContext world) {
        if (!runtime.actionContexts().sourceActionsAllowed(world)) {
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

    private void openManagedFolder(
            Path destination,
            RuntimeConfigurationGate.Permit permit) {
        boolean clientCallbackScheduled = false;
        try {
            Files.createDirectories(destination);
            if (!runtime.isClosed()) {
                runtime.minecraft().execute(() -> {
                    try {
                        if (!runtime.isClosed()) {
                            Util.getPlatform().openPath(destination);
                        }
                    } catch (RuntimeException exception) {
                        runtime.logFailure(
                                "Managed backup folder could not be opened",
                                exception);
                    } finally {
                        permit.close();
                    }
                });
                clientCallbackScheduled = true;
            }
        } catch (IOException | RuntimeException exception) {
            runtime.logFailure("Managed backup folder could not be opened", exception);
        } finally {
            if (!clientCallbackScheduled) {
                permit.close();
            }
        }
    }

    private void transitionToRestoredWorld(
            Screen returnTo,
            RestoreBackupResult result,
            boolean playImmediately) {
        Objects.requireNonNull(returnTo, "returnTo");
        Objects.requireNonNull(result, "result");
        Minecraft minecraft = runtime.minecraft();
        minecraft.execute(() -> {
            if (runtime.isClosed()) {
                return;
            }
            Optional<String> storageName = validatedRestoredStorageName(result);
            if (storageName.isEmpty()) {
                return;
            }
            String name = storageName.orElseThrow();
            RestoredWorldTransition.afterLeavingActiveWorld(
                    this::hasActiveWorldSession,
                    () -> minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE),
                    leftActiveWorld -> finishRestoredWorldTransition(
                            leftActiveWorld ? new TitleScreen() : returnTo,
                            name,
                            playImmediately,
                            leftActiveWorld));
        });
    }

    private void finishRestoredWorldTransition(
            Screen returnTo,
            String storageName,
            boolean playImmediately,
            boolean leftActiveWorld) {
        if (playImmediately) {
            playRestoredWorld(returnTo, storageName, leftActiveWorld);
        } else {
            showRestoredWorldSelection(returnTo, storageName);
        }
    }

    private boolean hasActiveWorldSession() {
        Minecraft minecraft = runtime.minecraft();
        return minecraft.hasSingleplayerServer()
                || minecraft.level != null
                || minecraft.getConnection() != null;
    }

    private void showRestoredWorldSelection(Screen returnTo, String storageName) {
        Minecraft minecraft = runtime.minecraft();
        SelectWorldScreen screen = new SelectWorldScreen(returnTo);
        minecraft.setScreenAndShow(screen);
        RestoredWorldSelection.install(screen, storageName);
    }

    private void playRestoredWorld(
            Screen returnTo,
            String storageName,
            boolean selectOnFailure) {
        Minecraft minecraft = runtime.minecraft();
        try (LevelStorageSource.LevelStorageAccess ignored =
                minecraft.getLevelSource().validateAndCreateAccess(storageName)) {
            // Validation and a clean session close happen before vanilla starts the world.
        } catch (IOException | ContentValidationException exception) {
            runtime.logFailure("Restored world validation failed", exception);
            handleRestoredWorldPlayFailure(returnTo, storageName, selectOnFailure);
            return;
        }
        minecraft.createWorldOpenFlows().openWorld(
                storageName,
                () -> handleRestoredWorldPlayFailure(
                        returnTo,
                        storageName,
                        selectOnFailure));
    }

    private void handleRestoredWorldPlayFailure(
            Screen returnTo,
            String storageName,
            boolean selectRestoredWorld) {
        if (selectRestoredWorld) {
            showRestoredWorldSelection(returnTo, storageName);
        } else {
            runtime.minecraft().setScreenAndShow(returnTo);
        }
    }

    private Optional<String> validatedRestoredStorageName(RestoreBackupResult result) {
        try {
            Path restored = result.restoredWorldDirectory().toAbsolutePath().normalize();
            Path base = runtime.minecraft().getLevelSource().getBaseDir()
                    .toAbsolutePath()
                    .normalize();
            Path fileName = restored.getFileName();
            if (fileName == null
                    || !base.equals(restored.getParent())
                    || !Files.isDirectory(restored, LinkOption.NOFOLLOW_LINKS)) {
                runtime.logFailure(
                        "Restored world path could not be validated",
                        new IOException(
                                "Restored world is outside the active saves directory"));
                return Optional.empty();
            }
            return Optional.of(fileName.toString());
        } catch (SecurityException exception) {
            runtime.logFailure("Restored world path could not be validated", exception);
            return Optional.empty();
        }
    }

    private Path managedPath(
            RuntimeState state,
            BackupWorldContext world,
            Optional<BackupRow> selected) {
        if (selected.isPresent() && selected.orElseThrow().zip().durable()) {
            return state.storagePaths().zipDirectory().resolve(world.worldId().toString());
        }
        if (selected.isPresent() && selected.orElseThrow().git().durable()) {
            return state.gitBackend().repositoryFor(world.worldId());
        }
        if (state.config().zip().enabled()) {
            return state.storagePaths().zipDirectory();
        }
        return state.gitBackend().repositoryFor(world.worldId());
    }

    private void openBrowserForBackup(BackupId backupId, CommandAction action) {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(action, "action");
        RuntimeState state = runtime.states().currentOrNull();
        if (state == null || runtime.isClosed()) {
            return;
        }
        state.coordinator().findBackup(backupId).whenComplete((record, throwable) -> {
            if (throwable != null || record == null || record.isEmpty()) {
                if (throwable != null) {
                    runtime.logFailure(action + " target could not be loaded", throwable);
                }
                return;
            }
            openBrowserForRecord(state, record.orElseThrow(), backupId, action);
        });
    }

    private void openBrowserForRecord(
            RuntimeState state,
            BackupRecord record,
            BackupId backupId,
            CommandAction action) {
        contextForRecord(state, record).whenComplete((context, throwable) -> {
            if (throwable != null || context == null || context.isEmpty()) {
                if (throwable != null) {
                    runtime.logFailure(action + " world could not be resolved", throwable);
                }
                return;
            }
            runtime.minecraft().execute(() -> showBackupAction(
                    context.orElseThrow(),
                    backupId,
                    action));
        });
    }

    private void showBackupAction(
            BackupWorldContext context,
            BackupId backupId,
            CommandAction action) {
        if (runtime.isClosed()) {
            return;
        }
        PauseScreen parent = new PauseScreen(true);
        BackupBrowserScreen browser = switch (action) {
            case RESTORE -> BackupBrowserScreen.forRestore(
                    parent,
                    context,
                    runtime,
                    backupId);
            case DELETE -> BackupBrowserScreen.forDelete(
                    parent,
                    context,
                    runtime,
                    backupId);
        };
        runtime.minecraft().setScreenAndShow(browser);
    }

    private CompletionStage<Optional<BackupWorldContext>> contextForRecord(
            RuntimeState state,
            BackupRecord record) {
        BackupWorldContext missingSource = missingSourceContext(record);
        BackupWorldContext active = runtime.currentLiveWorld();
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
            return runtime.resolveWorld(new BackupWorldSelection(
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
        Path worldsDirectory = runtime.minecraft().getLevelSource().getBaseDir()
                .toAbsolutePath()
                .normalize();
        String storageName = ".worldarchive-missing-" + record.manifest().worldId();
        BackupWorldContext context = new BackupWorldContext(
                record.manifest().worldId(),
                worldsDirectory.resolve(storageName),
                worldsDirectory,
                storageName,
                record.manifest().worldName());
        return runtime.actionContexts().markActionOnly(context);
    }

    private enum CommandAction {
        RESTORE,
        DELETE
    }
}
