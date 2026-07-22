package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipBackupBackendTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void backendRunsOnItsExecutorAndObserverFailuresCannotCauseFalseFailure() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        byte[] contents = "world data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(world.resolve("level.dat"), contents);
        BackupManifest manifest = manifest(contents);
        List<String> callbackThreads = new CopyOnWriteArrayList<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zip-backup-worker");
            thread.setDaemon(true);
            return thread;
        })) {
            ZipBackupBackend backend = new ZipBackupBackend(
                    temporaryDirectory.resolve("archives"), executor);
            var result = backend.createBackup(new BackupCapture(world, manifest), progress -> {
                callbackThreads.add(Thread.currentThread().getName());
                throw new IllegalStateException("observer failed");
            }).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(DestinationStatus.SUCCESS, result.status());
            assertTrue(result.artifactId().isPresent());
            assertFalse(callbackThreads.isEmpty());
            assertTrue(callbackThreads.stream().allMatch(name -> name.equals("zip-backup-worker")));
        }
    }

    @Test
    void recoverableStoreFailureCompletesWithDestinationFailure() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "unexpected file");
        BackupManifest staleManifest = emptyManifest();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ZipBackupBackend backend = new ZipBackupBackend(
                    temporaryDirectory.resolve("archives"), executor);

            var result = backend.createBackup(
                    new BackupCapture(world, staleManifest), progress -> {
                    }).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(DestinationStatus.FAILED, result.status());
            assertEquals("World contents no longer match the prepared backup manifest",
                    result.message().orElseThrow());
            assertTrue(result.artifactId().isEmpty());
        }
    }

    @Test
    void resolverRoutesEachWorldToItsConfiguredZipRoot() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("routed-world"));
        byte[] contents = "routed world data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(world.resolve("level.dat"), contents);
        WorldId worldId = WorldId.create();
        Path defaultRoot = temporaryDirectory.resolve("default-archives");
        Path overrideRoot = temporaryDirectory.resolve("override-archives");
        ZipBackupStoreResolver stores = candidate -> new ZipBackupStore(
                candidate.equals(worldId) ? overrideRoot : defaultRoot);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ZipBackupBackend backend = new ZipBackupBackend(stores, executor);

            var result = backend.createBackup(
                    new BackupCapture(world, manifest(contents, worldId)),
                    ignored -> {}).toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(DestinationStatus.SUCCESS, result.status());
            assertTrue(Files.isDirectory(overrideRoot.resolve(worldId.toString())));
            assertFalse(Files.exists(defaultRoot));
        }
    }

    private static BackupManifest manifest(byte[] contents) {
        return manifest(contents, WorldId.create());
    }

    private static BackupManifest manifest(byte[] contents, WorldId worldId) {
        ZipInventory inventory = ZipInventory.create(List.of(new ZipInventoryEntry(
                "level.dat", contents.length, sha256(contents))));
        return BackupManifest.create(
                BackupId.create(),
                worldId,
                "Test World",
                Optional.empty(),
                Instant.parse("2026-07-17T20:15:30.123Z"),
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                ZipDigests.contentSha256(inventory.files()),
                inventory.inventorySha256());
    }

    private static BackupManifest emptyManifest() {
        ZipInventory inventory = ZipInventory.create(List.of());
        return BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Test World",
                Instant.parse("2026-07-17T20:15:30.123Z"),
                BackupTrigger.MANUAL,
                0,
                0,
                ZipDigests.contentSha256(inventory.files()));
    }

    private static String sha256(byte[] contents) {
        var digest = ZipDigests.sha256();
        digest.update(contents);
        return ZipDigests.hex(digest.digest());
    }
}
