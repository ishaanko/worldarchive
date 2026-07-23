package dev.ishaankot.worldarchive.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileBackupDeletionRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deletionIntentSurvivesReloadAndCanBeExplicitlyRestored() throws Exception {
        Path file = temporaryDirectory.resolve("deleted.txt");
        BackupId deleted = BackupId.create();
        FileBackupDeletionRegistry registry = new FileBackupDeletionRegistry(file);

        registry.record(deleted);

        FileBackupDeletionRegistry reloaded = new FileBackupDeletionRegistry(file);
        assertTrue(reloaded.contains(deleted));
        reloaded.restore(deleted);
        assertFalse(new FileBackupDeletionRegistry(file).contains(deleted));
    }
}
