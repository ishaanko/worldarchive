package dev.ishaanko.worldarchive.storage.zip;

import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.CREATED_AT;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.NO_PROGRESS;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.bytes;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.capture;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.checksumOf;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.files;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipBackupStoreTest {
    private final WorldId worldId = WorldId.create();

    @TempDir
    Path temporaryDirectory;

    @Test
    void archiveFilenameIsReadableAndKeepsItsManagedIdentity() throws Exception {
        Path world = ZipTestFixtures.world(temporaryDirectory.resolve("world"), files("level.dat", "world data"));

        ZipBackupArtifact artifact = store().create(capture(world, WorldId.create(), CREATED_AT), NO_PROGRESS);

        assertEquals(
                "2026-09-01_12-00-00Z - Test World - Snapshot 世界 - " + artifact.manifest().backupId() + ".zip",
                artifact.archivePath().getFileName().toString());
    }

    @Test
    void archiveFilenameFitsPortableComponentByteLimits() {
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "界".repeat(255),
                Optional.of("🌍".repeat(64)),
                CREATED_AT,
                BackupTrigger.MANUAL,
                0,
                0,
                0,
                "0".repeat(64),
                "0".repeat(64),
                Optional.empty());

        String filename = ZipBackupStore.archiveFilename(manifest);

        assertTrue(filename.getBytes(StandardCharsets.UTF_8).length <= 255);
        assertTrue(filename.endsWith(manifest.backupId() + ".zip"));
    }

    @Test
    void roundTripKeepsBytesUnicodeCompressibleFilesAndEmptyFolders() throws Exception {
        Map<String, byte[]> worldFiles = new TreeMap<>();
        worldFiles.put("level.dat", bytes(ZipArchiveReader.BUFFER_BYTES * 2 + 37, 1));
        worldFiles.put("région-世界/данные.txt", "héllo 世界\n".getBytes(StandardCharsets.UTF_8));
        worldFiles.put("region/r.0.0.mca", new byte[4 * 1_024 * 1_024]);
        worldFiles.put("data/session.lock", "only the one next to level.dat is left out".getBytes(StandardCharsets.UTF_8));
        worldFiles.put("empty.txt", new byte[0]);
        Path world = ZipTestFixtures.world(temporaryDirectory.resolve("world"), worldFiles);
        Files.createDirectories(world.resolve("empty").resolve("nested"));
        ZipBackupStore store = store();

        ZipBackupArtifact artifact = store.create(capture(world, WorldId.create(), CREATED_AT), NO_PROGRESS);

        assertEquals(List.of(artifact), store.listArchives());
        assertEquals(List.of(artifact.archivePath().getFileName(), checksumOf(artifact).getFileName()),
                fileNames(artifact.archivePath().getParent()));
        ZipVerification verification = store.verify(artifact.archivePath());
        assertEquals(List.of(), verification.problems());
        assertEquals(List.of(), verification.warnings());
        assertEquals(Optional.of(artifact.manifest()), verification.manifest());
        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        assertEquals(artifact.manifest(), store.materialize(artifact.archivePath(), staging));
        assertSameFiles(worldFiles, staging);
        assertTrue(Files.isDirectory(staging.resolve("empty").resolve("nested")));
        assertEquals(
                List.of(new ZipArchiveSize(
                        artifact.manifest().backupId(),
                        artifact.archivePath(),
                        Files.size(artifact.archivePath()) + Files.size(checksumOf(artifact)))),
                store.listSizes(artifact.manifest().worldId()));
    }

    @Test
    void aBackupThatLostItsChecksumFileIsStillListedVerifiedAndRestored() throws Exception {
        ZipBackupStore store = store();
        ZipBackupArtifact kept = createSimple(store, "kept", CREATED_AT);
        ZipBackupArtifact bare = createSimple(store, "bare", CREATED_AT.plusSeconds(1));
        Files.delete(checksumOf(bare));
        Files.writeString(bare.archivePath().resolveSibling(
                "2026-09-01_10-00-00Z - Test World - Manual - " + BackupId.create() + ".zip"), "not a zip");

        assertEquals(List.of(bare, kept), store.listArchives());
        ZipVerification verification = store.verify(bare.archivePath());
        assertTrue(verification.valid(), verification.problems().toString());
        assertEquals(1, verification.warnings().size());
        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        store.materialize(bare.archivePath(), staging);
        assertEquals("bare", Files.readString(staging.resolve("level.dat")));
    }

    @Test
    void anArchiveThatCannotBeReadDoesNotHideTheOthers() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        ZipBackupStore store = store();
        ZipBackupArtifact readable = createSimple(store, "readable", CREATED_AT);
        ZipBackupArtifact locked = createSimple(store, "locked", CREATED_AT.plusSeconds(1));
        Files.setPosixFilePermissions(locked.archivePath(), PosixFilePermissions.fromString("---------"));
        Assumptions.assumeFalse(Files.isReadable(locked.archivePath()), "permissions do not apply to this user");

        assertEquals(List.of(readable), store.listArchives());
        assertThrows(IOException.class, () -> store.verify(locked.archivePath()));
    }

    /**
     * A create removes what interrupted creates and imports left. A checksum file whose archive
     * is missing stays: a sync tool may deliver the archive later, or the player moved it away
     * for a moment.
     */
    @Test
    void aCreateRemovesLeftoversOfInterruptedOperationsAndNothingElse() throws Exception {
        ZipBackupStore store = store();
        ZipBackupArtifact existing = createSimple(store, "existing", CREATED_AT);
        Path folder = existing.archivePath().getParent();
        String other = "2026-09-01_11-00-00Z - Test World - Manual - " + BackupId.create() + ".zip";
        String legacy = "20260717T201530123Z_" + BackupId.create() + ".zip";
        List<Path> leftovers = List.of(
                Files.writeString(folder.resolve(other + ".partial"), "half an archive"),
                Files.writeString(folder.resolve(other + ".sha256.partial"), "half a checksum"),
                Files.writeString(folder.resolve(legacy + ".importing"), "half an import"),
                Files.writeString(folder.resolve(legacy + ".sha256.importing"), "half a checksum"),
                Files.writeString(folder.resolve(".worldarchive.guard"), ""));
        Path userFile = Files.writeString(folder.resolve("notes.zip.partial"), "not ours");
        Path awayForAMoment = Files.writeString(folder.resolve(legacy + ".sha256"), "a checksum without its archive");

        ZipBackupArtifact next = createSimple(store, "next", CREATED_AT.plusSeconds(60));

        assertTrue(leftovers.stream().noneMatch(Files::exists), leftovers.toString());
        assertTrue(Files.exists(userFile));
        assertTrue(Files.exists(awayForAMoment));
        assertTrue(store.verify(existing.archivePath()).valid());
        assertEquals(List.of(next, existing), store.listArchives());
    }

    @Test
    void deletionRemovesOnlyTheSelectedPair() throws Exception {
        ZipBackupStore store = store();
        ZipBackupArtifact first = createSimple(store, "first", CREATED_AT);
        ZipBackupArtifact second = createSimple(store, "second", CREATED_AT.plusSeconds(1));
        Path partial = Files.writeString(first.archivePath().resolveSibling("unrelated.zip.partial"), "partial");

        assertTrue(store.delete(first.archivePath()));

        assertFalse(Files.exists(first.archivePath()));
        assertFalse(Files.exists(checksumOf(first)));
        assertFalse(store.delete(first.archivePath()));
        assertTrue(Files.exists(second.archivePath()));
        assertTrue(Files.exists(checksumOf(second)));
        assertTrue(Files.exists(partial));
        assertThrows(ZipBackupException.class, () -> store.delete(temporaryDirectory.resolve("outside.zip")));
    }

    /** A world's folder that is a link to another drive: a create writes through it, and list and delete follow it too. */
    @Test
    void aWorldFolderThatIsALinkIsListedAndItsArchiveDeleted() throws Exception {
        Path elsewhere = Files.createDirectories(temporaryDirectory.resolve("other-drive/world-archives"));
        try {
            Files.createSymbolicLink(Files.createDirectories(temporaryDirectory.resolve("archives"))
                    .resolve(worldId.toString()), elsewhere);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }
        ZipBackupStore store = store();
        ZipBackupArtifact linked = createSimple(store, "linked", CREATED_AT);

        assertEquals(List.of(linked), store.listArchives());
        assertEquals(1, store.listSizes(worldId).size());
        assertTrue(store.delete(linked.archivePath()));
        assertEquals(List.of(), fileNames(elsewhere).stream().filter(name -> name.toString().endsWith(".zip")).toList());
    }

    /** The drive is unmounted: its mount point, the chosen ZIP folder, is there, but the world's folder is not. */
    @Test
    void deletingFromAChosenFolderWhoseWorldFolderIsGoneFailsAndCreatesNothing() throws Exception {
        ZipBackupStore store = new ZipBackupStore(
                Files.createDirectories(temporaryDirectory.resolve("usb")), FolderOrigin.CHOSEN);
        ZipBackupArtifact away = createSimple(store, "away", CREATED_AT);
        Path worldFolder = away.archivePath().getParent();
        Files.move(worldFolder, temporaryDirectory.resolve("unmounted"));

        ZipBackupException failure = assertThrows(ZipBackupException.class, () -> store.delete(away.archivePath()));

        assertTrue(failure.getMessage().contains("cannot be reached"), failure.getMessage());
        assertFalse(Files.exists(worldFolder));
    }

    /** A world's folder removed by hand from the default ZIP folder holds nothing to delete, and nothing is created. */
    @Test
    void aWorldFolderRemovedByHandFromTheDefaultFolderCountsAsAlreadyDeleted() throws Exception {
        ZipBackupStore store = store();
        ZipBackupArtifact removed = createSimple(store, "removed", CREATED_AT);
        Path worldFolder = removed.archivePath().getParent();
        Files.move(worldFolder, temporaryDirectory.resolve("trash"));

        assertFalse(store.delete(removed.archivePath()));
        assertFalse(Files.exists(worldFolder));
    }

    /** Only the checksum file was left under the listed name: the delete removes it and says the archive was gone. */
    @Test
    void aDeleteThatFindsOnlyTheChecksumFileSaysTheArchiveWasAlreadyGone() throws Exception {
        ZipBackupStore store = store();
        ZipBackupArtifact moved = createSimple(store, "moved", CREATED_AT);
        Files.delete(moved.archivePath());

        assertFalse(store.delete(moved.archivePath()));
        assertFalse(Files.exists(checksumOf(moved)));
    }

    /**
     * An archive copied without its checksum file and cut at its central directory cannot be
     * opened by other ZIP programs, so Verify and import refuse it; a restore still reads it.
     */
    @Test
    void anArchiveCutAtItsCentralDirectoryWithoutItsChecksumFailsVerifyButStillRestores() throws Exception {
        ZipBackupStore store = store();
        ZipBackupArtifact cut = createSimple(store, "cut", CREATED_AT);
        Files.delete(checksumOf(cut));
        cutAtCentralDirectory(cut.archivePath());
        assertThrows(IOException.class, () -> new ZipFile(cut.archivePath().toFile()).close());

        ZipVerification verification = store.verify(cut.archivePath());
        ZipImportScan scan = new ZipImportScanner().scan(store.root());

        assertFalse(verification.valid(), verification.warnings().toString());
        assertEquals(List.of(), scan.candidates());
        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        assertEquals(cut.manifest(), store.materialize(cut.archivePath(), staging));
        assertEquals("cut", Files.readString(staging.resolve("level.dat")));
    }

    /** More entries than an end record can count: Verify reads the count of the ZIP64 end record. */
    @Test
    void anArchiveWithAZip64EndRecordVerifiesWithoutItsChecksum() throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        for (int index = 0; index < 70_000; index++) {
            files.put(String.format(Locale.ROOT, "data/f%05d", index), new byte[0]);
        }
        BackupManifest manifest =
                ZipTestFixtures.manifest(WorldId.create(), ZipTestFixtures.inventory(files), CREATED_AT);
        Path archive = ZipTestFixtures.writeArchive(
                temporaryDirectory.resolve("archives"), manifest, ZipTestFixtures.wellFormedEntries(manifest, files));
        Files.delete(archive.resolveSibling(archive.getFileName() + ".sha256"));

        ZipVerification verification = store().verify(archive);

        assertTrue(verification.valid(), verification.problems().toString());
    }

    @Test
    void restoreRequiresAnExistingEmptyFolder() throws Exception {
        ZipBackupStore store = store();
        ZipBackupArtifact artifact = createSimple(store, "world", CREATED_AT);
        Path nonempty = Files.createDirectory(temporaryDirectory.resolve("nonempty"));
        Files.writeString(nonempty.resolve("keep.txt"), "keep");

        assertThrows(ZipBackupException.class, () -> store.materialize(artifact.archivePath(), nonempty));
        assertThrows(IOException.class,
                () -> store.materialize(artifact.archivePath(), temporaryDirectory.resolve("absent")));
        assertEquals(List.of(Path.of("keep.txt")), fileNames(nonempty));
    }

    @Test
    void restoreStopsBeforeWritingWhenTheDriveIsTooSmall() throws Exception {
        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        long usable = Files.getFileStore(staging).getUsableSpace();
        Assumptions.assumeTrue(usable > 0 && usable < Long.MAX_VALUE / 2);
        Map<String, byte[]> files = files("level.dat", "tiny");
        BackupManifest honest = ZipTestFixtures.manifest(WorldId.create(), ZipTestFixtures.inventory(files), CREATED_AT);
        BackupManifest tooLarge = BackupManifest.create(
                honest.backupId(), honest.worldId(), honest.worldName(), honest.label(), honest.createdAt(),
                honest.trigger(), 1, usable * 2, 1, honest.contentSha256(), honest.inventorySha256(), Optional.empty());
        Path archive = ZipTestFixtures.writeArchive(
                temporaryDirectory.resolve("archives"), tooLarge, ZipTestFixtures.wellFormedEntries(tooLarge, files));

        ZipBackupException failure = assertThrows(ZipBackupException.class,
                () -> store().materialize(archive, staging));

        assertTrue(failure.getMessage().contains("not enough free space"), failure.getMessage());
        assertEquals(List.of(), fileNames(staging));
    }

    /** Truncates the archive where its central directory starts, as a copy that stopped early would. */
    private static void cutAtCentralDirectory(Path archive) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        long offset = ByteBuffer.wrap(bytes, bytes.length - 6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
            file.setLength(offset);
        }
    }

    private ZipBackupStore store() {
        return new ZipBackupStore(temporaryDirectory.resolve("archives"), FolderOrigin.DEFAULT);
    }

    /** A backup of a one-file world whose level.dat holds the name; every one is of the same world. */
    private ZipBackupArtifact createSimple(ZipBackupStore store, String name, Instant createdAt) throws IOException {
        Path world = ZipTestFixtures.world(temporaryDirectory.resolve("world-" + name), files("level.dat", name));
        return store.create(capture(world, worldId, createdAt), NO_PROGRESS);
    }

    private static List<Path> fileNames(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(Path::getFileName)
                    .filter(name -> !name.toString().equals(".worldarchive.lock"))
                    .sorted()
                    .toList();
        }
    }

    private static void assertSameFiles(Map<String, byte[]> expected, Path root) throws IOException {
        Map<String, byte[]> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                actual.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((path, contents) -> assertArrayEquals(contents, actual.get(path), path));
    }
}
