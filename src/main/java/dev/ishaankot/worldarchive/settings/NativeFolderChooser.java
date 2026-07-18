package dev.ishaankot.worldarchive.settings;

import java.nio.file.Path;
import java.util.Optional;

/** Non-blocking, best-effort cancellable platform folder selection contract. */
@FunctionalInterface
public interface NativeFolderChooser {
    CancellableRequest<FolderSelectionResult> chooseFolder(
            String title,
            Optional<Path> initialDirectory);
}
