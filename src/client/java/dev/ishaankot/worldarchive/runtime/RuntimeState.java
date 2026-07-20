package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.core.CreateBackupRequest;
import dev.ishaankot.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaankot.worldarchive.storage.git.WorldGitSnapshotStore;
import java.util.Objects;

/** One immutable, internally consistent runtime service graph. */
record RuntimeState(
        WorldArchiveConfig config,
        RuntimeStoragePaths storagePaths,
        WorldGitSnapshotStore gitBackend,
        RuntimeDestinationSelector selector,
        SerializedBackupCoordinator coordinator) {
    RuntimeState {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(storagePaths, "storagePaths");
        Objects.requireNonNull(gitBackend, "gitBackend");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(coordinator, "coordinator");
    }

    boolean enabledDestinations(CreateBackupRequest request) {
        return !selector.select(request).isEmpty();
    }

    void close() {
        gitBackend.close();
    }
}
