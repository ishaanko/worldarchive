package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.importing.ImportArtifactBinding;
import dev.ishaankot.worldarchive.importing.ImportSource;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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

    @Test
    void rejectsEveryArchiveWhenBackupIdentityIsDuplicated() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("duplicate-world"));
        Files.writeString(world.resolve("level.dat"), "world-data");
        Path sourceRoot = temporaryDirectory.resolve("duplicate-archives");
        ZipBackupArtifact source = new ZipBackupStore(sourceRoot)
                .create(new BackupCapture(world, manifest(world)));
        Files.copy(source.archivePath(), sourceRoot.resolve("duplicate.zip"));

        ZipImportScan scan = new ZipImportScanner().scan(sourceRoot);

        assertTrue(scan.candidates().isEmpty());
        assertEquals(2, scan.issues().size());
        assertTrue(scan.issues().stream().allMatch(issue ->
                issue.message().contains("same backup identity")));
    }

    @Test
    void rejectsAnOversizedChecksumSidecar() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("sidecar-world"));
        Files.writeString(world.resolve("level.dat"), "world-data");
        Path sourceRoot = temporaryDirectory.resolve("sidecar-source");
        ZipBackupArtifact source = new ZipBackupStore(sourceRoot)
                .create(new BackupCapture(world, manifest(world)));
        Files.writeString(
                source.checksumPath(),
                "0".repeat(ZipLimits.MAXIMUM_CHECKSUM_BYTES + 1));

        ZipImportScan scan = new ZipImportScanner().scan(sourceRoot);

        assertTrue(scan.candidates().isEmpty());
        assertEquals(1, scan.issues().size());
    }

    @Test
    void boundedSidecarReaderRejectsMalformedUtf8() throws Exception {
        Path sidecar = temporaryDirectory.resolve("malformed.sha256");
        Files.write(sidecar, new byte[] {(byte) 0xff});

        assertThrows(IOException.class, () -> ZipImportScanner.readSidecar(sidecar));
    }

    @Test
    void boundedSidecarReaderPreservesNormalManagedText() throws Exception {
        Path sidecar = temporaryDirectory.resolve("normal.sha256");
        String expected = "0".repeat(64) + "  archive 世界.zip\n";
        Files.writeString(sidecar, expected, StandardCharsets.UTF_8);

        assertEquals(expected, ZipImportScanner.readSidecar(sidecar));
    }

    @Test
    void importWaitsForCreateThroughTheSharedWorldLock() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("locked-world"));
        Files.writeString(world.resolve("level.dat"), "world-data");
        BackupManifest manifest = manifest(world);
        Path sourceRoot = temporaryDirectory.resolve("locked-source");
        new ZipBackupStore(sourceRoot).create(new BackupCapture(world, manifest));
        ZipImportCandidate candidate = new ZipImportScanner()
                .scan(sourceRoot)
                .candidates()
                .getFirst();
        Path managedRoot = temporaryDirectory.resolve("locked-managed");
        Path managedWorld = managedRoot.resolve(manifest.worldId().toString());
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        ZipStoreHooks hooks = new ZipStoreHooks() {
            @Override
            public void archiveCompleted(Path partialArchive) throws IOException {
                createEntered.countDown();
                try {
                    if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out while the test held ZIP creation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("ZIP creation test wait was interrupted", exception);
                }
            }
        };
        ZipBackupStore creating = new ZipBackupStore(managedRoot, hooks);
        ZipBackupStore importing = new ZipBackupStore(managedRoot);
        ReentrantLock operationLock = ZipBackupStore.processLock(managedWorld);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ZipBackupArtifact> created = executor.submit(
                    () -> creating.create(new BackupCapture(world, manifest)));
            assertTrue(createEntered.await(5, TimeUnit.SECONDS));
            CountDownLatch importStarted = new CountDownLatch(1);
            Future<ZipBackupArtifact> imported;
            try {
                imported = executor.submit(() -> {
                    importStarted.countDown();
                    return importing.importCopy(candidate);
                });
                assertTrue(importStarted.await(5, TimeUnit.SECONDS));
                assertTrue(
                        waitForQueuedThread(operationLock),
                        "ZIP import did not wait for the active create operation");
            } finally {
                releaseCreate.countDown();
            }

            ZipBackupArtifact createdArtifact = created.get(10, TimeUnit.SECONDS);
            ZipBackupArtifact importedArtifact = imported.get(10, TimeUnit.SECONDS);
            assertEquals(createdArtifact, importedArtifact);
            assertEquals(List.of(createdArtifact), importing.listCompleteArchives());
        }
    }

    @Test
    void resumesImportFromAMatchingChecksumOnlyDestination() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("resumed-world"));
        Files.writeString(world.resolve("level.dat"), "world-data");
        BackupManifest manifest = manifest(world);
        Path sourceRoot = temporaryDirectory.resolve("resumed-source");
        new ZipBackupStore(sourceRoot).create(new BackupCapture(world, manifest));
        ZipImportCandidate candidate = new ZipImportScanner()
                .scan(sourceRoot)
                .candidates()
                .getFirst();
        Path managedRoot = temporaryDirectory.resolve("resumed-managed");
        Path managedWorld = Files.createDirectories(
                managedRoot.resolve(manifest.worldId().toString()));
        String archiveName = ZipBackupStore.archiveFilename(manifest);
        Path checksum = managedWorld.resolve(archiveName + ".sha256");
        String checksumText =
                candidate.archiveSha256() + "  " + archiveName + System.lineSeparator();
        Files.writeString(checksum, checksumText);
        ZipBackupStore managed = new ZipBackupStore(managedRoot);

        ZipBackupArtifact imported = managed.importCopy(candidate);

        assertTrue(Files.isRegularFile(imported.archivePath()));
        assertEquals(checksumText, Files.readString(checksum));
        assertTrue(Files.isRegularFile(managedWorld.resolve(".worldarchive.lock")));
        assertTrue(managed.verify(imported.archivePath()).valid());
        assertEquals(List.of(imported), managed.listCompleteArchives());
    }

    private static boolean waitForQueuedThread(ReentrantLock lock) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (lock.hasQueuedThreads()) {
                return true;
            }
            Thread.sleep(10);
        }
        return lock.hasQueuedThreads();
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
