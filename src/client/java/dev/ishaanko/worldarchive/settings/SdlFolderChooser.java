package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.ui.model.FolderSelectionResult;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shows the SDL folder picker that ships with Minecraft. SDL requires the dialog to open on the
 * main thread and reports the choice through a callback that may run on any thread, including
 * the main thread inside the call that opened the dialog.
 */
public final class SdlFolderChooser {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final String PICKER_FAILED = "The native folder picker failed; type an absolute path instead";

    private final Executor mainThreadQueue;

    private final LongSupplier windowHandle;

    /**
     * @param mainThreadQueue queues work for the main thread and never runs it in the calling
     *        thread, such as {@code Minecraft.schedule}
     * @param windowHandle the game window the dialog belongs to
     */
    public SdlFolderChooser(Executor mainThreadQueue, LongSupplier windowHandle) {
        this.mainThreadQueue = Objects.requireNonNull(mainThreadQueue, "mainThreadQueue");
        this.windowHandle = Objects.requireNonNull(windowHandle, "windowHandle");
    }

    /** Opens the dialog; the result completes when the player chooses or dismisses it. */
    public CompletableFuture<FolderSelectionResult> chooseFolder(String title, Optional<Path> initialDirectory) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(initialDirectory, "initialDirectory");
        CompletableFuture<FolderSelectionResult> completion = new CompletableFuture<>();
        mainThreadQueue.execute(() -> {
            if (!completion.isDone()) {
                show(title, initialDirectory, completion);
            }
        });
        return completion;
    }

    private void show(
            String title,
            Optional<Path> initialDirectory,
            CompletableFuture<FolderSelectionResult> completion) {
        DialogCallback callback = new DialogCallback(completion);
        try {
            int properties = SDLProperties.SDL_CreateProperties();
            try {
                SDLProperties.SDL_SetStringProperty(
                        properties, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title);
                SDLProperties.SDL_SetPointerProperty(
                        properties,
                        SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER,
                        windowHandle.getAsLong());
                initialDirectory.ifPresent(directory -> SDLProperties.SDL_SetStringProperty(
                        properties,
                        SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING,
                        directory.toAbsolutePath().toString()));
                SDLDialog.SDL_ShowFileDialogWithProperties(
                        SDLDialog.SDL_FILEDIALOG_OPENFOLDER, callback, MemoryUtil.NULL, properties);
            } finally {
                SDLProperties.SDL_DestroyProperties(properties);
            }
        } catch (LinkageError exception) {
            callback.free();
            completion.complete(new FolderSelectionResult.Failed(
                    "Native folder selection is unavailable; type an absolute path instead"));
        } catch (RuntimeException exception) {
            callback.free();
            LOGGER.warn("The SDL folder picker could not be opened", exception);
            completion.complete(new FolderSelectionResult.Failed(PICKER_FAILED));
        }
    }

    /**
     * SDL invokes this exactly once per dialog, on error, cancel, or selection. The native
     * trampoline is freed by a task queued for the main thread, which runs only after this
     * invocation has returned, even when SDL calls back inside the call that opened the dialog.
     */
    private final class DialogCallback extends SDL_DialogFileCallback {
        private final CompletableFuture<FolderSelectionResult> completion;

        private DialogCallback(CompletableFuture<FolderSelectionResult> completion) {
            this.completion = completion;
        }

        @Override
        public void invoke(long userdata, long fileList, int filter) {
            try {
                completion.complete(read(fileList));
            } finally {
                mainThreadQueue.execute(this::free);
            }
        }
    }

    /** Translates SDL's file list: NULL means failure, an empty list means the user cancelled. */
    private static FolderSelectionResult read(long fileList) {
        if (fileList == MemoryUtil.NULL) {
            LOGGER.warn("The SDL folder picker failed: {}", SDLError.SDL_GetError());
            return new FolderSelectionResult.Failed(PICKER_FAILED);
        }
        long first = MemoryUtil.memGetAddress(fileList);
        if (first == MemoryUtil.NULL) {
            return new FolderSelectionResult.Cancelled();
        }
        try {
            return new FolderSelectionResult.Selected(Path.of(MemoryUtil.memUTF8(first)));
        } catch (InvalidPathException exception) {
            return new FolderSelectionResult.Failed("The selected folder path is invalid");
        }
    }
}
