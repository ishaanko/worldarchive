package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.importing.ImportArtifactBinding;
import dev.ishaankot.worldarchive.importing.ImportSource;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZipImportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void scansCopiesAndLinksOnlyPinnedWorldArchiveArchives() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world-data");
        BackupManifest manifest = manifest(world);
        Path sourceRoot = temporaryDirectory.resolve("source-archives");
        ZipBackupArtifact source = new ZipBackupStore(sourceRoot)
                .create(new BackupCapture(world, manifest));
        Files.writeString(sourceRoot.resolve("not-a-backup.zip"), "not a zip");

        ZipImportScan scan = new ZipImportScanner().scan(sourceRoot);

        assertEquals(1, scan.candidates().size());
        assertEquals(1, scan.issues().size());
        ZipImportCandidate candidate = scan.candidates().getFirst();
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("managed"));
        ZipBackupArtifact copied = managed.importCopy(candidate);
        assertTrue(managed.verify(copied.archivePath()).valid());
        assertEquals(manifest, copied.manifest());

        String locator = sourceRoot.relativize(source.archivePath())
                .toString().replace('\\', '/');
        ImportSource linked = ImportSource.zipLink(
                ImportSourceId.derived("ZIP_LINK\0" + sourceRoot),
                sourceRoot,
                Map.of(manifest.backupId(), new ImportArtifactBinding(
                        manifest.worldId(),
                        manifest.backupId(),
                        locator,
                        source.archiveSha256())));
        ImportArtifactBinding binding = linked.artifact(manifest.backupId()).orElseThrow();
        LinkedZipArtifactAccess access = new LinkedZipArtifactAccess();
        assertTrue(access.verify(linked, binding, manifest).valid());
        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        access.materialize(linked, binding, manifest, staging);
        assertEquals("world-data", Files.readString(staging.resolve("level.dat")));

        Files.write(source.archivePath(), new byte[] {1}, StandardOpenOption.APPEND);
        assertFalse(access.verify(linked, binding, manifest).valid());
        assertTrue(Files.isRegularFile(source.archivePath()));
    }

    private static BackupManifest manifest(Path world) throws Exception {
        List<ZipInventoryEntry> files = new ArrayList<>();
        for (ZipSourceScanner.SourceEntry entry : ZipSourceScanner.snapshot(world).entries()) {
            if (!entry.directory()) {
                files.add(new ZipInventoryEntry(
                        entry.relativePath(), entry.size(), ZipDigests.sha256(entry.path())));
            }
        }
        ZipInventory inventory = ZipInventory.create(files);
        return BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Imported World",
                Optional.of("Recovery"),
                Instant.parse("2026-07-21T20:00:00Z"),
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                ZipDigests.contentSha256(inventory.files()),
                inventory.inventorySha256());
    }
}
