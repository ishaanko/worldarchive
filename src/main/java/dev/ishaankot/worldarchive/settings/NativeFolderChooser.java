package dev.ishaankot.worldarchive.settings;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Non-blocking platform folder selection contract. */
@FunctionalInterface
public interface NativeFolderChooser {
    CompletionStage<FolderSelectionResult> chooseFolder(String title, Optional<Path> initialDirectory);
}
