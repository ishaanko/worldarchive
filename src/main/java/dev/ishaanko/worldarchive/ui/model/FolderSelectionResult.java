package dev.ishaanko.worldarchive.ui.model;

import java.nio.file.Path;
import java.util.Objects;

/** The outcome of a platform folder picker. */
public sealed interface FolderSelectionResult {
    /** The user selected a directory. */
    record Selected(Path path) implements FolderSelectionResult {
        public Selected {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
    }

    /** The user intentionally dismissed the picker. */
    record Cancelled() implements FolderSelectionResult {
    }

    /** The picker failed or is not available, and the current setting is unchanged. */
    record Failed(String message) implements FolderSelectionResult {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }
}
