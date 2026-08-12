package dev.ishaanko.worldarchive.catalog;

import dev.ishaanko.worldarchive.model.BackupId;
import java.io.IOException;

/** Durable intent that prevents automatic catalog repair from reviving a manual deletion. */
public interface BackupDeletionRegistry {
    BackupDeletionRegistry NONE = new BackupDeletionRegistry() {
        @Override
        public boolean contains(BackupId backupId) {
            return false;
        }

        @Override
        public void record(BackupId backupId) {
        }

        @Override
        public void restore(BackupId backupId) {
        }
    };

    boolean contains(BackupId backupId) throws IOException;

    void record(BackupId backupId) throws IOException;

    /** Clears a deletion marker after an explicit user-selected reimport. */
    void restore(BackupId backupId) throws IOException;
}
