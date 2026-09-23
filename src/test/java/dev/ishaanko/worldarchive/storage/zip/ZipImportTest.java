package dev.ishaanko.worldarchive.storage.zip;

import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.CREATED_AT;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.NO_PROGRESS;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.capture;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.files;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.TestCaptures;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZipImportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void offersOnlyWorldArchiveBackupsAndImportsThemOnce() throws Exception {
        Path source = temporaryDirectory.resolve("downloads");
        ZipBackupArtifact backup = new ZipBackupStore(source, FolderOrigin.DEFAULT)
                .create(worldCapture("world", "world-data"), NO_PROGRESS);
        Files.writeString(source.resolve("not-a-zip.zip"), "not a zip");
        try (ZipOutputStream modpack = new ZipOutputStream(Files.newOutputStream(source.resolve("modpack.zip")))) {
            modpack.putNextEntry(new ZipEntry("mods/readme.txt"));
            modpack.write("a modpack".getBytes(StandardCharsets.UTF_8));
        }
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("managed"), FolderOrigin.DEFAULT);

        ZipImportScan scan = new ZipImportScanner().scan(source);
        ZipBackupArtifact imported = managed.importCopy(scan.candidates().getFirst());
        ZipBackupArtifact again = managed.importCopy(scan.candidates().getFirst());

        assertEquals(1, scan.candidates().size());
        assertEquals(2, scan.issues().size());
        assertEquals(backup.manifest(), imported.manifest());
        assertEquals(imported, again);
        assertTrue(managed.verify(imported.archivePath()).valid());
        assertArrayEquals(Files.readAllBytes(backup.archivePath()), Files.readAllBytes(imported.archivePath()));
        assertTrue(Files.isRegularFile(backup.archivePath()));
    }

    @Test
    void identicalCopiesCountOnceAndDifferentFilesForOneBackupAreRefused() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        ZipBackupArtifact copied =
                new ZipBackupStore(source.resolve("archives"), FolderOrigin.DEFAULT)
                        .create(worldCapture("copied", "a"), NO_PROGRESS);
        Path copy = Files.createDirectories(source.resolve("archives - Copy").resolve("folder"));
        Files.copy(copied.archivePath(), copy.resolve(copied.archivePath().getFileName()));
        BackupCapture claimed = worldCapture("claimed", "b");
        new ZipBackupStore(source.resolve("first"), FolderOrigin.DEFAULT).create(claimed, NO_PROGRESS);
        Path other = ZipTestFixtures.world(temporaryDirectory.resolve("other"), files("level.dat", "different data"));
        WorldInventory otherInventory = TestCaptures.inventoryOf(other);
        BackupManifest sameBackupOtherData = BackupManifest.create(
                claimed.manifest().backupId(), claimed.manifest().worldId(), "Test World", Optional.empty(),
                CREATED_AT, BackupTrigger.MANUAL, otherInventory.fileCount(), otherInventory.byteCount(), 1,
                otherInventory.contentSha256(), otherInventory.inventorySha256(), Optional.empty());
        new ZipBackupStore(source.resolve("second"), FolderOrigin.DEFAULT)
                .create(new BackupCapture(other, sameBackupOtherData, otherInventory), NO_PROGRESS);

        ZipImportScan scan = new ZipImportScanner().scan(source);

        assertEquals(List.of(copied.manifest()), scan.candidates().stream().map(ZipImportCandidate::manifest).toList());
        assertEquals(2, scan.issues().size());
    }

    @Test
    void leftoversOfAnInterruptedImportDoNotBlockTheNextOne() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        new ZipBackupStore(source, FolderOrigin.DEFAULT)
                .create(worldCapture("world", "world-data"), NO_PROGRESS);
        ZipImportCandidate candidate = new ZipImportScanner().scan(source).candidates().getFirst();
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("managed"), FolderOrigin.DEFAULT);
        ManagedZipArchive target = ManagedZipArchive.of(managed.root(), candidate.manifest());
        Files.createDirectories(target.folder());
        Files.writeString(target.archive().resolveSibling(target.name() + ".importing"), "half a copy");
        Files.writeString(target.checksum(), candidate.archiveSha256() + "  " + target.name() + "\r\n");

        ZipBackupArtifact imported = managed.importCopy(candidate);

        assertTrue(managed.verify(imported.archivePath()).valid());
        assertEquals(List.of(target.name(), target.name() + ".sha256"), fileNames(target.folder()));
    }

    @Test
    void aFileThatChangedAfterThePreviewIsNotImported() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        ZipBackupArtifact backup = new ZipBackupStore(source, FolderOrigin.DEFAULT)
                .create(worldCapture("world", "world-data"), NO_PROGRESS);
        ZipImportCandidate candidate = new ZipImportScanner().scan(source).candidates().getFirst();
        Files.write(backup.archivePath(), new byte[] {1}, StandardOpenOption.APPEND);
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("managed"), FolderOrigin.DEFAULT);

        assertThrows(ZipBackupException.class, () -> managed.importCopy(candidate));

        assertEquals(List.of(), fileNames(ManagedZipArchive.of(managed.root(), candidate.manifest()).folder()));
    }

    @Test
    void aDifferentFileAlreadyStoredForTheBackupIsNeverReplaced() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        new ZipBackupStore(source, FolderOrigin.DEFAULT)
                .create(worldCapture("world", "world-data"), NO_PROGRESS);
        ZipImportCandidate candidate = new ZipImportScanner().scan(source).candidates().getFirst();
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("managed"), FolderOrigin.DEFAULT);
        Path stored = ManagedZipArchive.of(managed.root(), candidate.manifest()).archive();
        Files.createDirectories(stored.getParent());
        Files.writeString(stored, "another file under the same name");

        assertThrows(ZipBackupException.class, () -> managed.importCopy(candidate));

        assertEquals("another file under the same name", Files.readString(stored));
    }

    private BackupCapture worldCapture(String name, String level) throws Exception {
        Path world = ZipTestFixtures.world(temporaryDirectory.resolve("world-" + name), files("level.dat", level));
        return capture(world, WorldId.create(), CREATED_AT);
    }

    private static List<String> fileNames(Path folder) throws Exception {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> !name.equals(".worldarchive.lock"))
                    .sorted()
                    .toList();
        }
    }
}
