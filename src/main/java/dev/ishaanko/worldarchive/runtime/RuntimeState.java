package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaanko.worldarchive.importing.FileBackupImportService;
import dev.ishaanko.worldarchive.recovery.BackupRecoveryService;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.management.ManagedStorageService;
import java.util.Objects;

/**
 * The services built from one version of the settings. {@link ServiceGraph} builds a new state for
 * each settings change; work that started on an older state finishes there.
 */
public record RuntimeState(
        WorldArchiveConfig config,
        RuntimeStoragePaths storagePaths,
        WorldGitSnapshotStore git,
        RuntimeDestinationSelector selector,
        SerializedBackupCoordinator coordinator,
        BackupRecoveryService recovery,
        FileBackupImportService imports,
        ManagedStorageService storage) {
    public RuntimeState {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(storagePaths, "storagePaths");
        Objects.requireNonNull(git, "git");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(imports, "imports");
        Objects.requireNonNull(storage, "storage");
    }

    /** Releases the import previews prepared on this state. */
    void close() {
        imports.close();
    }
}
