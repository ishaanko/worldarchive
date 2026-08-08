package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipBackupStoreAuditTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-17T21:15:30.123Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void completedArchiveMustPassPerFileInspectionBeforePublication() throws Exception {
        Fixture fixture = fixture("inspection");
        byte[] expected = Files.readAllBytes(fixture.world().resolve("level.dat"));
        ZipInventory inventory = ZipInventory.create(List.of(new ZipInventoryEntry(
                "level.dat", expected.length, sha256(expected))));
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void archiveCompleted(Path partialArchive) throws IOException {
                rewriteArchive(partialArchive, zip -> {
                    writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY,
                            ZipMetadataCodec.encodeManifest(fixture.manifest()));
                    writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX, new byte[0]);
                    writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX + "level.dat",
                            "tampered".getBytes(StandardCharsets.UTF_8));
                    writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                            ZipMetadataCodec.encodeInventory(inventory));
                });
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(ZipBackupException.class,
                () -> store.create(new BackupCapture(fixture.world(), fixture.manifest())));

        assertTrue(store.listCompleteArchives().isEmpty());
        assertFalse(hasFileWithSuffix(fixture.root(), ".partial"));
        assertFalse(hasFileWithSuffix(fixture.root(), ".zip"));
    }

    @Test
    void publishedArchiveMutationCannotReturnSuccess() throws Exception {
        Fixture fixture = fixture("publish-interference");
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void archivePublished(Path archive) throws IOException {
                Files.write(archive, new byte[] {42}, StandardOpenOption.APPEND);
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(ZipBackupException.class,
                () -> store.create(new BackupCapture(fixture.world(), fixture.manifest())));

        assertTrue(store.listCompleteArchives().isEmpty());
        assertFalse(hasFileWithSuffix(fixture.root(), ".zip"));
        assertFalse(hasFileWithSuffix(fixture.root(), ".sha256"));
    }

    @Test
    void postInspectionMutationIsDetectedBeforePublication() throws Exception {
        Fixture fixture = fixture("inspection-race");
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void archiveInspected(Path partialArchive) throws IOException {
                Files.write(partialArchive, new byte[] {7}, StandardOpenOption.APPEND);
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(ZipBackupException.class,
                () -> store.create(new BackupCapture(fixture.world(), fixture.manifest())));

        assertTrue(store.listCompleteArchives().isEmpty());
        assertFalse(hasFileWithSuffix(fixture.root(), ".zip"));
        assertFalse(hasFileWithSuffix(fixture.root(), ".partial"));
    }

    @Test
    void managedWorldReplacementCannotPublishOrStrandAPartialArchive() throws Exception {
        Fixture fixture = fixture("directory-replacement");
        Path displacedDirectory = fixture.root().resolve(
                fixture.manifest().worldId() + ".displaced");
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void archiveCompleted(Path partialArchive) throws IOException {
                Path worldDirectory = partialArchive.getParent();
                Files.move(worldDirectory, displacedDirectory);
                Files.createDirectory(worldDirectory);
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(IOException.class,
                () -> store.create(new BackupCapture(fixture.world(), fixture.manifest())));

        assertTrue(store.listCompleteArchives().isEmpty());
        assertFalse(hasFileWithSuffix(fixture.root(), ".partial"));
        assertFalse(hasFileWithSuffix(fixture.root(), ".zip"));
    }

    @Test
    void interruptedCreationLeavesNoPublishedOrPartialArtifact() throws Exception {
        Fixture fixture = fixture("interrupted");
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void archiveCompleted(Path partialArchive) throws IOException {
                throw new InterruptedIOException("simulated interruption");
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(InterruptedIOException.class,
                () -> store.create(new BackupCapture(fixture.world(), fixture.manifest())));

        assertTrue(store.listCompleteArchives().isEmpty());
        assertFalse(hasFileWithSuffix(fixture.root(), ".partial"));
    }

    @Test
    void simulatedDiskFullFailureCannotPublishOrReportAnArtifact() throws Exception {
        Fixture fixture = fixture("disk-full");
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void archiveCompleted(Path partialArchive) throws IOException {
                throw new FileSystemException(
                        partialArchive.toString(), null, "simulated disk full");
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(FileSystemException.class,
                () -> store.create(new BackupCapture(fixture.world(), fixture.manifest())));

        assertTrue(store.listCompleteArchives().isEmpty());
        assertFalse(hasFileWithSuffix(fixture.root(), ".partial"));
        assertFalse(hasFileWithSuffix(fixture.root(), ".zip"));
    }

    @Test
    void checksumIsPublishedBeforeTheCompletedArchive() throws Exception {
        Fixture fixture = fixture("publication-order");
        AtomicBoolean checksumObservedFirst = new AtomicBoolean();
        AtomicBoolean checksumPresentWithArchive = new AtomicBoolean();
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void checksumPublished(Path archive, Path checksum) {
                checksumObservedFirst.set(!Files.exists(archive) && Files.isRegularFile(checksum));
            }

            @Override
            public void archivePublished(Path archive) {
                checksumPresentWithArchive.set(Files.isRegularFile(
                        Path.of(archive + ".sha256")));
            }
        };

        ZipBackupArtifact artifact = new ZipBackupStore(fixture.root(), hooks).create(
                new BackupCapture(fixture.world(), fixture.manifest()));

        assertTrue(checksumObservedFirst.get());
        assertTrue(checksumPresentWithArchive.get());
        assertTrue(Files.isRegularFile(artifact.archivePath()));
        assertTrue(Files.isRegularFile(artifact.checksumPath()));
    }

    @Test
    void stalePartialsAreRemovedBeforeARetry() throws Exception {
        Fixture fixture = fixture("stale-partial");
        Path directory = Files.createDirectories(
                fixture.root().resolve(fixture.manifest().worldId().toString()));
        String filename = ZipBackupStore.archiveFilename(fixture.manifest());
        Files.writeString(directory.resolve(filename + ".partial"), "incomplete archive");
        Files.writeString(
                directory.resolve(filename + ".sha256.partial"), "incomplete checksum");

        ZipBackupArtifact artifact = new ZipBackupStore(fixture.root()).create(
                new BackupCapture(fixture.world(), fixture.manifest()));

        assertTrue(Files.isRegularFile(artifact.archivePath()));
        assertTrue(Files.isRegularFile(artifact.checksumPath()));
        assertFalse(hasFileWithSuffix(fixture.root(), ".partial"));
    }

    @Test
    void orphanedArchiveRecoversItsChecksumIdempotently() throws Exception {
        Fixture fixture = fixture("orphan-recovery");
        ZipBackupStore store = new ZipBackupStore(fixture.root());
        BackupCapture capture = new BackupCapture(fixture.world(), fixture.manifest());
        ZipBackupArtifact first = store.create(capture);
        Object archiveKey = Files.readAttributes(
                first.archivePath(),
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        Files.delete(first.checksumPath());

        ZipBackupArtifact recovered = store.create(capture);

        assertEquals(first, recovered);
        assertEquals(archiveKey, Files.readAttributes(
                recovered.archivePath(),
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey());
        assertTrue(store.verify(recovered.archivePath()).valid());
    }

    @Test
    void restoreFailureRollsBackExtractedEntries() throws Exception {
        Fixture fixture = fixture("restore-rollback");
        ZipBackupArtifact artifact = new ZipBackupStore(fixture.root()).create(
                new BackupCapture(fixture.world(), fixture.manifest()));
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void restoreEntryExtracted(Path target) throws IOException {
                throw new IOException("simulated restore interruption");
            }
        };
        Path staging = Files.createDirectory(temporaryDirectory.resolve("rollback-staging"));

        assertThrows(IOException.class,
                () -> new ZipBackupStore(fixture.root(), hooks).materialize(
                        artifact.archivePath(), staging));

        try (var entries = Files.list(staging)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void sourceTreeChangeDuringStreamingCannotPublish() throws Exception {
        Fixture fixture = fixture("source-tree-change");
        AtomicBoolean changed = new AtomicBoolean();
        ZipBackupStore store = new ZipBackupStore(fixture.root());

        assertThrows(ZipBackupException.class, () -> store.create(
                new BackupCapture(fixture.world(), fixture.manifest()), completed -> {
                    if (changed.compareAndSet(false, true)) {
                        try {
                            Files.writeString(fixture.world().resolve("late-file.dat"), "late");
                        } catch (IOException exception) {
                            throw new UncheckedTestIOException(exception);
                        }
                    }
                }));

        assertTrue(store.listCompleteArchives().isEmpty());
        assertFalse(hasFileWithSuffix(fixture.root(), ".partial"));
        assertFalse(hasFileWithSuffix(fixture.root(), ".zip"));
    }

    @Test
    void restoreDetectsOriginalReplacementAndExtractsNothing() throws Exception {
        Fixture fixture = fixture("restore-replacement");
        ZipBackupArtifact artifact = new ZipBackupStore(fixture.root()).create(
                new BackupCapture(fixture.world(), fixture.manifest()));
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void restoreCopyCompleted(Path archive, Path privateCopy) throws IOException {
                Path replacement = archive.resolveSibling("replacement.tmp");
                Files.copy(archive, replacement, StandardCopyOption.REPLACE_EXISTING);
                Files.write(replacement, new byte[] {13}, StandardOpenOption.APPEND);
                Files.move(replacement, archive, StandardCopyOption.REPLACE_EXISTING);
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);
        Path staging = Files.createDirectory(temporaryDirectory.resolve("restore-staging"));

        assertThrows(ZipBackupException.class,
                () -> store.materialize(artifact.archivePath(), staging));

        try (var entries = Files.list(staging)) {
            assertTrue(entries.findAny().isEmpty());
        }
        try (var entries = Files.list(staging.getParent())) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".worldarchive-restore-")));
        }
    }

    @Test
    void archiveDeleteFailureLeavesCompletePairDiscoverable() throws Exception {
        Fixture fixture = fixture("delete-archive-failure");
        ZipBackupArtifact artifact = new ZipBackupStore(fixture.root()).create(
                new BackupCapture(fixture.world(), fixture.manifest()));
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void beforeArchiveDelete(Path archive) throws IOException {
                throw new IOException("simulated archive delete failure");
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(IOException.class, () -> store.delete(artifact));

        assertTrue(Files.exists(artifact.archivePath()));
        assertTrue(Files.exists(artifact.checksumPath()));
        assertTrue(store.listCompleteArchives().stream().anyMatch(candidate -> candidate.equals(artifact)));
    }

    @Test
    void checksumDeleteFailureCannotHideAnArchiveSurvivor() throws Exception {
        Fixture fixture = fixture("delete-checksum-failure");
        ZipBackupArtifact artifact = new ZipBackupStore(fixture.root()).create(
                new BackupCapture(fixture.world(), fixture.manifest()));
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void beforeChecksumDelete(Path checksum) throws IOException {
                throw new IOException("simulated checksum delete failure");
            }
        };
        ZipBackupStore store = new ZipBackupStore(fixture.root(), hooks);

        assertThrows(IOException.class, () -> store.delete(artifact));

        assertFalse(Files.exists(artifact.archivePath()));
        assertTrue(Files.exists(artifact.checksumPath()));
        assertTrue(store.listCompleteArchives().isEmpty());
    }

    @Test
    void managedLinkAncestorIsRejectedAcrossReadRestoreAndDelete() throws Exception {
        Fixture fixture = fixture("managed-link");
        ZipBackupArtifact artifact = new ZipBackupStore(fixture.root()).create(
                new BackupCapture(fixture.world(), fixture.manifest()));
        Path linkedRoot = temporaryDirectory.resolve("linked-archive-root");
        boolean linked = createDirectoryAlias(linkedRoot, fixture.root());
        Assumptions.assumeTrue(linked, "Directory links and junctions are unavailable");
        try {
            Path linkedArchive = linkedRoot.resolve(fixture.root().relativize(artifact.archivePath()));
            ZipBackupStore linkedStore = new ZipBackupStore(linkedRoot);
            Path staging = Files.createDirectory(temporaryDirectory.resolve("linked-staging"));

            assertFalse(linkedStore.verify(linkedArchive).valid());
            assertThrows(ZipBackupException.class,
                    () -> linkedStore.materialize(linkedArchive, staging));
            assertThrows(ZipBackupException.class, () -> linkedStore.delete(linkedArchive));
            assertThrows(ZipBackupException.class, linkedStore::listCompleteArchives);
            assertTrue(Files.exists(artifact.archivePath()));
            assertTrue(Files.exists(artifact.checksumPath()));
        } finally {
            Files.deleteIfExists(linkedRoot);
        }
    }

    @Test
    void nonexistentDestinationThroughLinkAncestorIsRejectedBeforeCreation() throws Exception {
        Fixture fixture = fixture("nonexistent-link");
        Path realParent = Files.createDirectory(temporaryDirectory.resolve("real-parent"));
        Path linkedParent = temporaryDirectory.resolve("linked-parent");
        boolean linked = createDirectoryAlias(linkedParent, realParent);
        Assumptions.assumeTrue(linked, "Directory links and junctions are unavailable");
        try {
            Path destination = linkedParent.resolve("future").resolve("archives");
            ZipBackupStore store = new ZipBackupStore(destination);

            assertThrows(ZipBackupException.class,
                    () -> store.create(new BackupCapture(fixture.world(), fixture.manifest())));

            assertFalse(Files.exists(realParent.resolve("future")));
        } finally {
            Files.deleteIfExists(linkedParent);
        }
    }

    @Test
    void sourceDirectoryReparsePointIsRejected() throws Exception {
        Fixture fixture = fixture("source-reparse");
        Path external = Files.createDirectory(temporaryDirectory.resolve("source-reparse-external"));
        Files.writeString(external.resolve("outside.txt"), "outside");
        Path linkedSource = fixture.world().resolve("linked-source");
        boolean linked = createDirectoryAlias(linkedSource, external);
        Assumptions.assumeTrue(linked, "Directory links and junctions are unavailable");
        try {
            assertThrows(ZipBackupException.class,
                    () -> new ZipBackupStore(fixture.root()).create(
                            new BackupCapture(fixture.world(), fixture.manifest())));
            assertFalse(Files.exists(fixture.root()));
        } finally {
            Files.deleteIfExists(linkedSource);
        }
    }

    @Test
    void jdkZip64EntryCountArchiveVerifies() throws Exception {
        Path root = temporaryDirectory.resolve("zip64-root");
        ZipInventory inventory = ZipInventory.create(List.of());
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "ZIP64 World",
                CREATED_AT,
                BackupTrigger.MANUAL,
                0,
                0,
                ZipDigests.contentSha256(inventory.files()));
        Path archive = writeManagedPair(root, manifest, zip -> {
            writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY, ZipMetadataCodec.encodeManifest(manifest));
            for (int index = 0; index < 65_536; index++) {
                writeEntry(zip, "world/d" + index + "/", new byte[0]);
            }
            writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                    ZipMetadataCodec.encodeInventory(inventory));
        });

        ZipVerification verification = new ZipBackupStore(root).verify(archive);

        assertTrue(verification.valid(), () -> String.join("; ", verification.problems()));
    }

    @Test
    void manifestDigestsAreBoundToTheEmbeddedInventory() throws Exception {
        Path root = temporaryDirectory.resolve("manifest-digest-root");
        byte[] contents = "expected".getBytes(StandardCharsets.UTF_8);
        ZipInventory inventory = ZipInventory.create(List.of(new ZipInventoryEntry(
                "level.dat", contents.length, sha256(contents))));
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Digest World",
                Optional.empty(),
                CREATED_AT,
                BackupTrigger.MANUAL,
                1,
                contents.length,
                1,
                "0".repeat(64),
                inventory.inventorySha256());
        Path archive = writeManagedPair(root, manifest, zip -> {
            writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY,
                    ZipMetadataCodec.encodeManifest(manifest));
            writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX, new byte[0]);
            writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX + "level.dat", contents);
            writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                    ZipMetadataCodec.encodeInventory(inventory));
        });

        ZipVerification verification = new ZipBackupStore(root).verify(archive);

        assertFalse(verification.valid());
        assertTrue(verification.problems().contains(
                "Archive manifest digests do not match the ZIP inventory."));
    }

    @Test
    void unixSymlinkEntryIsRejectedBeforeRestore() throws Exception {
        Path root = temporaryDirectory.resolve("archive-symlink-root");
        byte[] contents = "target".getBytes(StandardCharsets.UTF_8);
        ZipInventory inventory = ZipInventory.create(List.of(new ZipInventoryEntry(
                "link.dat", contents.length, sha256(contents))));
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Symlink World",
                Optional.empty(),
                CREATED_AT,
                BackupTrigger.MANUAL,
                1,
                contents.length,
                1,
                ZipDigests.contentSha256(inventory.files()),
                inventory.inventorySha256());
        Path archive = writeManagedPair(root, manifest, zip -> {
            writeEntry(zip, ZipArchiveFormat.MANIFEST_ENTRY,
                    ZipMetadataCodec.encodeManifest(manifest));
            writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX, new byte[0]);
            writeEntry(zip, ZipArchiveFormat.WORLD_PREFIX + "link.dat", contents);
            writeEntry(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                    ZipMetadataCodec.encodeInventory(inventory));
        });
        markCentralEntryAsUnixSymlink(
                archive, ZipArchiveFormat.WORLD_PREFIX + "link.dat");
        rewriteChecksum(archive);
        ZipBackupStore store = new ZipBackupStore(root);
        Path staging = Files.createDirectory(temporaryDirectory.resolve("symlink-staging"));

        ZipVerification verification = store.verify(archive);

        assertFalse(verification.valid());
        assertTrue(verification.problems().contains(
                "Archive contains a symbolic-link or special entry."));
        assertThrows(ZipBackupException.class, () -> store.materialize(archive, staging));
        try (var entries = Files.list(staging)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    private Fixture fixture(String name) throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve(name + "-world"));
        byte[] contents = "expected".getBytes(StandardCharsets.UTF_8);
        Files.write(world.resolve("level.dat"), contents);
        ZipInventory inventory = ZipInventory.create(List.of(new ZipInventoryEntry(
                "level.dat", contents.length, sha256(contents))));
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Test World",
                Optional.empty(),
                CREATED_AT,
                BackupTrigger.MANUAL,
                1,
                contents.length,
                1,
                ZipDigests.contentSha256(inventory.files()),
                inventory.inventorySha256());
        return new Fixture(world, temporaryDirectory.resolve(name + "-archives"), manifest);
    }

    private static boolean createDirectoryAlias(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target, new java.nio.file.attribute.FileAttribute<?>[0]);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                return false;
            }
        }
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean hasFileWithSuffix(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) {
            return false;
        }
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().endsWith(suffix));
        }
    }

    private static void rewriteArchive(Path archive, Consumer<ZipOutputStream> contents)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(
                        archive,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE),
                StandardCharsets.UTF_8)) {
            try {
                contents.accept(zip);
            } catch (UncheckedTestIOException exception) {
                throw exception.cause();
            }
        }
    }

    private static void markCentralEntryAsUnixSymlink(Path archive, String entryName)
            throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        byte[] expectedName = entryName.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= bytes.length - 46; offset++) {
            if (littleEndianInt(bytes, offset) != 0x0201_4b50) {
                continue;
            }
            int nameLength = littleEndianShort(bytes, offset + 28);
            int nameOffset = offset + 46;
            if (nameLength != expectedName.length || nameOffset + nameLength > bytes.length) {
                continue;
            }
            boolean matches = true;
            for (int index = 0; index < nameLength; index++) {
                if (bytes[nameOffset + index] != expectedName[index]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            bytes[offset + 4] = 20;
            bytes[offset + 5] = 3;
            writeLittleEndianInt(bytes, offset + 38, 0120777 << 16);
            Files.write(archive, bytes);
            return;
        }
        throw new IOException("Test ZIP central entry was not found");
    }

    private static void rewriteChecksum(Path archive) throws IOException {
        String checksum = ZipDigests.sha256(archive);
        Files.writeString(
                Path.of(archive + ".sha256"),
                checksum + "  " + archive.getFileName() + "\n",
                StandardCharsets.UTF_8);
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return littleEndianShort(bytes, offset)
                | littleEndianShort(bytes, offset + 2) << 16;
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static Path writeManagedPair(
            Path root,
            BackupManifest manifest,
            Consumer<ZipOutputStream> contents) throws IOException {
        Path directory = Files.createDirectories(root.resolve(manifest.worldId().toString()));
        Path archive = directory.resolve(managedFilename(manifest));
        rewriteArchive(archive, contents);
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

    private record Fixture(Path world, Path root, BackupManifest manifest) {
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
