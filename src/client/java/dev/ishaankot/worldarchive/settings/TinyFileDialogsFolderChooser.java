package dev.ishaankot.worldarchive.settings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Runs the bundled native folder picker away from Minecraft's render thread. */
public final class TinyFileDialogsFolderChooser implements NativeFolderChooser {
    private final ExecutorService executor;

    public TinyFileDialogsFolderChooser(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CancellableRequest<FolderSelectionResult> chooseFolder(
            String title,
            Optional<Path> initialDirectory) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(initialDirectory, "initialDirectory");
        CompletableFuture<FolderSelectionResult> completion = new CompletableFuture<>();
        Future<?> task = executor.submit(() -> {
            if (!completion.isCancelled()) {
                completion.complete(open(title, initialDirectory));
            }
        });
        return new CancellableRequest<>(completion, () -> {
            task.cancel(true);
            completion.cancel(false);
        });
    }

    private static FolderSelectionResult open(String title, Optional<Path> initialDirectory) {
        try {
            String defaultPath = initialDirectory.map(Path::toString).orElse(null);
            String selection = TinyFileDialogs.tinyfd_selectFolderDialog(title, defaultPath);
            if (selection == null) {
                return new FolderSelectionResult.Cancelled();
            }
            return new FolderSelectionResult.Selected(Path.of(selection));
        } catch (InvalidPathException exception) {
            return new FolderSelectionResult.Failed("The selected folder path is invalid");
        } catch (LinkageError exception) {
            return new FolderSelectionResult.Unavailable(
                    "Native folder selection is unavailable; type an absolute path instead");
        } catch (RuntimeException exception) {
            return new FolderSelectionResult.Failed(
                    "The native folder picker failed; type an absolute path instead");
        }
    }
}
