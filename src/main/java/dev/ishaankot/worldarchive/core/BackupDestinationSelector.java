package dev.ishaankot.worldarchive.core;

import java.util.List;

/** Resolves an immutable destination plan for one create request. */
@FunctionalInterface
public interface BackupDestinationSelector {
    List<BackupBackend> select(CreateBackupRequest request);

    static BackupDestinationSelector fixed(List<BackupBackend> destinations) {
        List<BackupBackend> fixed = List.copyOf(destinations);
        return ignored -> fixed;
    }
}
