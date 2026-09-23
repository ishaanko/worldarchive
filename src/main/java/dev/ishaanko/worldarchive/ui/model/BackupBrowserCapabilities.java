package dev.ishaanko.worldarchive.ui.model;

import java.util.Objects;
import java.util.Optional;

/**
 * What the runtime knows about a world that its backup records cannot tell the browser: whether an
 * operation runs, whether the world folder is there, why a new backup cannot be made, whether the
 * Git remote and the backup folder are ready, and a warning to show.
 */
public record BackupBrowserCapabilities(
        boolean operationInProgress,
        boolean sourceAvailable,
        Optional<CreateBlock> createBlock,
        boolean gitRemoteConfigured,
        boolean managedFolderAvailable,
        Optional<String> warning) {
    public BackupBrowserCapabilities {
        Objects.requireNonNull(createBlock, "createBlock");
        Objects.requireNonNull(warning, "warning");
    }

    /** Why the settings let no new backup of the world be made, although its folder is there. */
    public enum CreateBlock {
        /** The player turned backups off for this world. */
        WORLD_OFF,
        /** No destination takes manual backups. */
        NO_DESTINATION,
        /** Only Git takes manual backups, and Git or Git LFS is missing. */
        GIT_MISSING,
        /** A backup folder is inside a world, so every backup is paused. */
        STORAGE_PROBLEM
    }

    /** A copy that reports an operation in progress, for while the browser itself waits. */
    public BackupBrowserCapabilities withOperationInProgress() {
        return new BackupBrowserCapabilities(
                true,
                sourceAvailable,
                createBlock,
                gitRemoteConfigured,
                managedFolderAvailable,
                warning);
    }
}
