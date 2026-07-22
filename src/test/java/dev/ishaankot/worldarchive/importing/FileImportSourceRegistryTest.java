package dev.ishaankot.worldarchive.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileImportSourceRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndUnlinksExternalArtifactsWithoutTouchingTheirFolder() throws Exception {
        Path linkedFolder = temporaryDirectory.resolve("linked").toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(linkedFolder);
        BackupId backupId = BackupId.create();
        WorldId worldId = WorldId.create();
        ImportSourceId sourceId = ImportSourceId.derived("ZIP_LINK\0" + linkedFolder);
        ImportSource source = ImportSource.zipLink(sourceId, linkedFolder, Map.of(
                backupId,
                new ImportArtifactBinding(
                        worldId,
                        backupId,
                        "nested/archive.zip",
                        "a".repeat(64))));
        FileImportSourceRegistry registry = new FileImportSourceRegistry(
                temporaryDirectory.resolve("sources.json"));

        registry.put(source);

        assertEquals(source, registry.find(sourceId).orElseThrow());
        assertEquals(source, new FileImportSourceRegistry(
                temporaryDirectory.resolve("sources.json")).list().getFirst());
        registry.unlink(sourceId, backupId);
        assertTrue(registry.find(sourceId).isEmpty());
        assertTrue(java.nio.file.Files.isDirectory(linkedFolder));
    }

    @Test
    void stableSourceIdentityIsIdempotent() {
        assertEquals(
                ImportSourceId.derived("same source"),
                ImportSourceId.derived("same source"));
    }
}
