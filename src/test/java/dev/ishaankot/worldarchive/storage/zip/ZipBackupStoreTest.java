package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipBackupStoreTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-17T20:15:30.123Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void archiveFilenameIsReadableAndKeepsItsManagedIdentity() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world data", StandardCharsets.UTF_8);
        BackupManifest manifest = manifestFor(world, CREATED_AT);

        ZipBackupArtifact artifact = new ZipBackupStore(temporaryDirectory.resolve("archives"))
                .create(new BackupCapture(world, manifest));

        assertEquals(
                "2026-07-17_20-15-30Z - Test World - Snapshot 世界 - "
                        + manifest.backupId() + ".zip",
                artifact.archivePath().getFileName().toString());
    }

    @Test
    void roundTripPreservesBytesUnicodeAndEmptyDirectories() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("world"));
        byte[] level = new byte[ZipDigests.COPY_BUFFER_BYTES * 2 + 37];
        for (int index = 0; index < level.length; index++) {
            level[index] = (byte) (index * 31);
        }
        Files.write(world.resolve("level.dat"), level);
        Path unicodeFile = world.resolve("région-世界").resolve("данные.txt");
        Files.createDirectories(unicodeFile.getParent());
        Files.writeString(unicodeFile, "héllo 世界\n", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("empty").resolve("nested"));
        Files.createDirectories(world.resolve(".worldarchive"));
        Files.writeString(world.resolve(".worldarchive").resolve("internal.txt"), "excluded");
        Files.writeString(world.resolve("session.lock"), "excluded");
        Path nestedSessionLock = world.resolve("data").resolve("session.lock");
        Files.createDirectories(nestedSessionLock.getParent());
        Files.writeString(nestedSessionLock, "retained");

        BackupManifest manifest = manifestFor(world, CREATED_AT);
        ZipBackupStore store = new ZipBackupStore(temporaryDirectory.resolve("archives"));
        ZipBackupArtifact artifact = store.create(new BackupCapture(world, manifest));

        assertTrue(Files.isRegularFile(artifact.archivePath()));
        assertTrue(Files.isRegularFile(artifact.checksumPath()));
        assertFalse(Files.exists(Path.of(artifact.archivePath() + ".partial")));
        assertFalse(Files.exists(Path.of(artifact.checksumPath() + ".partial")));
        assertEquals(manifest.sourceSha256(), artifact.manifest().sourceSha256());
        assertEquals(manifest, artifact.manifest());
        assertEquals(List.of(artifact), store.listCompleteArchives());

        ZipVerification verification = store.verify(artifact.archivePath());
        assertTrue(verification.valid(), () -> String.join("; ", verification.problems()));
        assertEquals(3, verification.verifiedFileCount());
        assertEquals(level.length
                        + "héllo 世界\n".getBytes(StandardCharsets.UTF_8).length
                        + "retained".getBytes(StandardCharsets.UTF_8).length,
                verification.verifiedByteCount());

        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        store.materialize(artifact.archivePath(), staging);
        assertArrayEquals(level, Files.readAllBytes(staging.resolve("level.dat")));
        assertEquals("héllo 世界\n", Files.readString(
                staging.resolve("région-世界").resolve("данные.txt"), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(staging.resolve("empty").resolve("nested")));
        assertFalse(Files.exists(staging.resolve(".worldarchive")));
        assertFalse(Files.exists(staging.resolve("session.lock")));
        assertEquals("retained", Files.readString(staging.resolve("data").resolve("session.lock")));
    }

    @Test
    void archiveTamperingBreaksSidecarVerification() throws Exception {
        CreatedBackup created = createSimpleBackup(CREATED_AT);
        Files.write(created.artifact().archivePath(), new byte[] {42}, StandardOpenOption.APPEND);

        ZipVerification verification = created.store().verify(created.artifact().archivePath());

        assertFalse(verification.valid());
        assertTrue(verification.problems().contains(
                "The ZIP archive checksum does not match its sidecar."));
    }

    @Test
    void perFileTamperingIsRejectedEvenWhenSidecarMatches() throws Exception {
        Path root = temporaryDirectory.resolve("archives");
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
        byte[] changed = "tampered".getBytes(StandardCharsets.UTF_8);
        ZipInventory inventory = ZipInventory.create(List.of(new ZipInventoryEntry(
                "level.dat", expected.length, sha256(expected))));
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "World",
                Optional.empty(),
                CREATED_AT,
                BackupTrigger.MANUAL,
                1,
                expected.length,
                1,
                ZipDigests.contentSha256(inventory.files()),
                inventory.inventorySha256());
        Path archive = writeManagedPair(root, manifest, zip -> {
            writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY, ZipMetadataCodec.encodeManifest(manifest));
            writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX, new byte[0]);
            writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX + "level.dat", changed);
            writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY, ZipMetadataCodec.encodeInventory(inventory));
        });

        ZipVerification verification = new ZipBackupStore(root).verify(archive);

        assertFalse(verification.valid());
        assertTrue(verification.problems().contains(
                "Archive entry checksum does not match its inventory."));
    }

    @Test
    void traversalEntryIsRejectedAndNeverMaterialized() throws Exception {
        Path root = temporaryDirectory.resolve("archives");
        BackupManifest manifest = emptyManifest(CREATED_AT);
        Path archive = writeManagedPair(root, manifest, zip -> {
            writeEntry(zip, "../escape.txt", "escape".getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY, ZipMetadataCodec.encodeManifest(manifest));
            writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                    ZipMetadataCodec.encodeInventory(ZipInventory.create(List.of())));
        });
        ZipBackupStore store = new ZipBackupStore(root);

        ZipVerification verification = store.verify(archive);

        assertFalse(verification.valid());
        assertTrue(verification.problems().contains("Archive contains an unsafe entry path."));
        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        assertThrows(ZipBackupException.class, () -> store.materialize(archive, staging));
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.txt")));
    }

    @Test
    void duplicateNormalizedPathsAreRejected() throws Exception {
        Path root = temporaryDirectory.resolve("archives");
        BackupManifest manifest = emptyManifest(CREATED_AT);
        Path archive = writeManagedPair(root, manifest, zip -> {
            writeEntry(zip, "world/Data/file.bin", new byte[] {1});
            writeEntry(zip, "world/data/file.bin", new byte[] {2});
            writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY, ZipMetadataCodec.encodeManifest(manifest));
            writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                    ZipMetadataCodec.encodeInventory(ZipInventory.create(List.of())));
        });

        ZipVerification verification = new ZipBackupStore(root).verify(archive);

        assertFalse(verification.valid());
        assertTrue(verification.problems().contains(
                "Archive contains duplicate normalized entry paths."));
    }

    @Test
    void fileAndDescendantPathConflictIsRejected() throws Exception {
        Path root = temporaryDirectory.resolve("archives");
        BackupManifest manifest = emptyManifest(CREATED_AT);
        Path archive = writeManagedPair(root, manifest, zip -> {
            writeEntry(zip, "world/data", new byte[] {1});
            writeEntry(zip, "world/data/child.bin", new byte[] {2});
            writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY,
                    ZipMetadataCodec.encodeManifest(manifest));
            writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                    ZipMetadataCodec.encodeInventory(ZipInventory.create(List.of())));
        });

        ZipVerification verification = new ZipBackupStore(root).verify(archive);

        assertFalse(verification.valid());
        assertTrue(verification.problems().contains(
                "Archive contains a file/directory path conflict."));
    }

    @Test
    void unsafePortablePathsAreRejected() {
        for (String path : List.of("../escape", "/absolute", "C:/drive", "a\\b", "a/./b", "a/../b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> PortableZipPath.validate(path, false), path);
        }
        assertThrows(IllegalArgumentException.class,
                () -> PortableZipPath.validate("world/nu\0l", false));
    }

    @Test
    void partialAndUnpairedFilesAreNotCatalogEntries() throws Exception {
        Path root = temporaryDirectory.resolve("archives");
        BackupManifest manifest = emptyManifest(CREATED_AT);
        Path worldDirectory = Files.createDirectories(root.resolve(manifest.worldId().toString()));
        String filename = managedFilename(manifest);
        Files.writeString(worldDirectory.resolve(filename + ".partial"), "partial");
        Files.writeString(worldDirectory.resolve(filename), "orphan archive");
        BackupManifest second = emptyManifest(CREATED_AT.plusSeconds(1));
        String secondName = managedFilename(second);
        Files.writeString(worldDirectory.resolve(secondName + ".sha256"), "0".repeat(64) + "  " + secondName + "\n");

        assertTrue(new ZipBackupStore(root).listCompleteArchives().isEmpty());
    }

    @Test
    void sourceSymlinkIsRejectedWhereSupported() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("world"));
        Path target = Files.writeString(world.resolve("target.txt"), "target");
        BackupManifest manifest = manifestFor(world, CREATED_AT);
        Path link = world.resolve("link.txt");
        boolean linkCreated;
        try {
            Files.createSymbolicLink(link, target.getFileName());
            linkCreated = true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            linkCreated = false;
        }
        Assumptions.assumeTrue(linkCreated, "Symbolic links are unavailable in this environment");

        assertThrows(ZipBackupException.class, () -> new ZipBackupStore(
                temporaryDirectory.resolve("archives")).create(new BackupCapture(world, manifest)));
    }

    @Test
    void restoreRequiresAnExistingEmptyStagingDirectory() throws Exception {
        CreatedBackup created = createSimpleBackup(CREATED_AT);
        Path nonempty = Files.createDirectory(temporaryDirectory.resolve("nonempty"));
        Files.writeString(nonempty.resolve("keep.txt"), "keep");
        Path absent = temporaryDirectory.resolve("absent");

        assertThrows(ZipBackupException.class,
                () -> created.store().materialize(created.artifact().archivePath(), nonempty));
        assertThrows(IOException.class,
                () -> created.store().materialize(created.artifact().archivePath(), absent));
        assertEquals("keep", Files.readString(nonempty.resolve("keep.txt")));
    }

    @Test
    void deletionIsIsolatedToTheSelectedCompletePair() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "data");
        ZipBackupStore store = new ZipBackupStore(temporaryDirectory.resolve("archives"));
        ZipBackupArtifact first = store.create(new BackupCapture(world, manifestFor(world, CREATED_AT)));
        ZipBackupArtifact second = store.create(new BackupCapture(
                world, manifestFor(world, CREATED_AT.plusSeconds(1))));
        Path unrelatedPartial = Files.writeString(
                first.archivePath().getParent().resolve("unrelated.zip.partial"), "partial");

        assertTrue(store.delete(first));

        assertFalse(Files.exists(first.archivePath()));
        assertFalse(Files.exists(first.checksumPath()));
        assertFalse(store.delete(first.archivePath()));
        assertTrue(Files.exists(second.archivePath()));
        assertTrue(Files.exists(second.checksumPath()));
        assertTrue(Files.exists(unrelatedPartial));
        assertThrows(ZipBackupException.class,
                () -> store.delete(temporaryDirectory.resolve("outside.zip")));
    }

    private CreatedBackup createSimpleBackup(Instant createdAt) throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("world-" + createdAt.toEpochMilli()));
        Files.writeString(world.resolve("level.dat"), "world data", StandardCharsets.UTF_8);
        ZipBackupStore store = new ZipBackupStore(temporaryDirectory.resolve("archives"));
        ZipBackupArtifact artifact = store.create(new BackupCapture(world, manifestFor(world, createdAt)));
        return new CreatedBackup(store, artifact);
    }

    private static BackupManifest manifestFor(Path world, Instant createdAt) throws IOException {
        List<ZipInventoryEntry> files = new java.util.ArrayList<>();
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
                "Test World",
                Optional.of("Snapshot 世界"),
                createdAt,
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                ZipDigests.contentSha256(inventory.files()),
                inventory.inventorySha256());
    }

    private static BackupManifest emptyManifest(Instant createdAt) {
        ZipInventory inventory = ZipInventory.create(List.of());
        return BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Test World",
                createdAt,
                BackupTrigger.MANUAL,
                0,
                0,
                ZipDigests.contentSha256(inventory.files()));
    }

    private static Path writeManagedPair(
            Path root,
            BackupManifest manifest,
            Consumer<ZipOutputStream> contents) throws IOException {
        Path directory = Files.createDirectories(root.resolve(manifest.worldId().toString()));
        Path archive = directory.resolve(managedFilename(manifest));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            try {
                contents.accept(zip);
            } catch (UncheckedTestIOException exception) {
                throw exception.cause();
            }
        }
        String checksum = ZipDigests.sha256(archive);
        Files.writeString(
                Path.of(archive + ".sha256"),
                checksum + "  " + archive.getFileName() + "\n",
                StandardCharsets.UTF_8);
        return archive;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] contents) {
        try {
            ZipEntry entry = new ZipEntry(name);
            zip.putNextEntry(entry);
            zip.write(contents);
            zip.closeEntry();
        } catch (IOException exception) {
            throw new UncheckedTestIOException(exception);
        }
    }

    private static String managedFilename(BackupManifest manifest) {
        String timestamp = manifest.createdAt().toString()
                .replace("-", "")
                .replace(":", "")
                .replace(".", "");
        timestamp = timestamp.substring(0, 15) + timestamp.substring(15, 18) + "Z";
        return timestamp + "_" + manifest.backupId() + ".zip";
    }

    private static String sha256(byte[] bytes) {
        var digest = ZipDigests.sha256();
        digest.update(bytes);
        return ZipDigests.hex(digest.digest());
    }

    private record CreatedBackup(ZipBackupStore store, ZipBackupArtifact artifact) {
    }

    private static final class UncheckedTestIOException extends RuntimeException {
        private final IOException cause;

        UncheckedTestIOException(IOException cause) {
            super(cause);
            this.cause = cause;
        }

        IOException cause() {
            return cause;
        }
    }
}
