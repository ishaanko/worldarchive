package dev.ishaanko.worldarchive.core;

import java.util.List;

/** Chooses the destinations of one backup request; each destination type appears at most once. */
@FunctionalInterface
public interface BackupDestinationSelector {
    List<BackupBackend> select(CreateBackupRequest request);
}
