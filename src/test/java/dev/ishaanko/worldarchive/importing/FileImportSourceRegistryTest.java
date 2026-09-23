package dev.ishaanko.worldarchive.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileImportSourceRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aRepositoryCollectsItsImportedBackupsAndGoesWhenTheLastIsUnlinked() throws Exception {
        Path file = temporaryDirectory.resolve("sources.json");
        ImportSourceId sourceId = ImportSourceId.derived("https://example.invalid/world.git");
        WorldId worldId = WorldId.create();
        BackupId first = BackupId.create();
        BackupId second = BackupId.create();
        FileImportSourceRegistry registry = new FileImportSourceRegistry(file);

        String location = "https://example.invalid/world.git";
        registry.put(ImportSource.git(sourceId, location, Map.of(first, binding(worldId, first))));
        registry.put(ImportSource.git(sourceId, location, Map.of(second, binding(worldId, second))));

        ImportSource merged = new FileImportSourceRegistry(file).find(sourceId).orElseThrow();
        assertEquals(ImportSourceMode.GIT_FULL_DOWNLOAD, merged.mode());
        assertEquals(Map.of(first, binding(worldId, first), second, binding(worldId, second)), merged.artifacts());
        registry.unlink(Map.of(first, sourceId));
        assertEquals(List.of(second), List.copyOf(registry.find(sourceId).orElseThrow().artifacts().keySet()));
        registry.unlink(Map.of(second, sourceId));
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void aLinkedFolderFromAnOlderReleaseStillDecodesAsItWasWritten() throws Exception {
        Path file = temporaryDirectory.resolve("sources.json");
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "sources": [
                    {
                      "id": "5b4a3928-1706-4f5e-8d4c-3b2a19087f6e",
                      "mode": "ZIP_LINK",
                      "location": "C:\\\\Users\\\\bob\\\\zips",
                      "artifacts": []
                    }
                  ]
                }
                """);

        ImportSource legacy = new FileImportSourceRegistry(file).list().getFirst();

        assertEquals(ImportSourceMode.ZIP_LINK, legacy.mode());
        assertEquals("C:\\Users\\bob\\zips", legacy.location());
    }

    private static ImportArtifactBinding binding(WorldId worldId, BackupId backupId) {
        return new ImportArtifactBinding(worldId, backupId, "refs/heads/backups/" + backupId, "a".repeat(40));
    }
}
