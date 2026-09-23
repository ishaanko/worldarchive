package dev.ishaanko.worldarchive.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaanko.worldarchive.model.BackupId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileBackupDeletionRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void marksSurviveAReloadAndGoWhenUnmarkedOrNoFileIsLeft() throws Exception {
        Path file = temporaryDirectory.resolve("deleted.txt");
        BackupId first = BackupId.create();
        BackupId second = BackupId.create();
        BackupId third = BackupId.create();
        new FileBackupDeletionRegistry(file).mark(List.of(first, second, third));

        FileBackupDeletionRegistry reloaded = new FileBackupDeletionRegistry(file);
        reloaded.unmark(List.of(first));
        reloaded.unmarkAllExcept(Set.of(second));

        assertEquals(Set.of(second), new FileBackupDeletionRegistry(file).marked());
    }

    @Test
    void aDamagedListIsMovedAsideAndOneFromANewerVersionIsRefused() throws Exception {
        Path damaged = temporaryDirectory.resolve("deleted.txt");
        Files.writeString(damaged, "worldarchive-deleted-backups-v1\nnot-a-backup-id\n");
        Path future = temporaryDirectory.resolve("future.txt");
        Files.writeString(future, "worldarchive-deleted-backups-v2\n");

        assertEquals(Set.of(), new FileBackupDeletionRegistry(damaged).marked());
        assertThrows(IOException.class, () -> new FileBackupDeletionRegistry(future).marked());

        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("deleted.txt.corrupt-")).count());
        }
        assertEquals("worldarchive-deleted-backups-v2\n", Files.readString(future));
    }
}
