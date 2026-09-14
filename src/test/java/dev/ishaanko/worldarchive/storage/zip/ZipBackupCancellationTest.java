package dev.ishaanko.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.core.BackupDestinationSelector;
import dev.ishaanko.worldarchive.core.BackupMaintenanceService;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaanko.worldarchive.core.FileWorldInventoryStore;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.core.SerializedBackupCoordinator;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A backup cancelled while the ZIP is written leaves no capture, archive, or catalog entry. */
final class ZipBackupCancellationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cancelDuringArchiveWriteLeavesNoFilesBehind() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(
                Files.createDirectory(world.resolve("region")).resolve("r.0.0.mca"), "chunks");
        Path captures = temporaryDirectory.resolve("captures");
        Path archives = temporaryDirectory.resolve("archives");
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ZipStoreHooks pauseWrite = new ZipStoreHooks() {
            @Override
            public void archiveCompleted(Path partialArchive) throws IOException {
                writing.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("ZIP write was interrupted");
                }
            }
        };
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            SerializedBackupCoordinator coordinator = new SerializedBackupCoordinator(
                    catalog,
                    new FileSystemBackupCaptureFactory(captures),
                    new FileWorldInventoryStore(temporaryDirectory.resolve("inventories")),
                    BackupDestinationSelector.fixed(List.of(new ZipBackupBackend(
                            new ZipBackupStore(archives, pauseWrite), executor))),
                    unusedMaintenance(),
                    executor,
                    Clock.systemUTC());
            WorldId worldId = WorldId.create();
            CompletionStage<BackupResult> operation = coordinator.createBackup(
                    new CreateBackupRequest(worldId, world, "Cancelled", BackupTrigger.MANUAL),
                    ProgressListener.NO_OP);
            assertTrue(writing.await(10, TimeUnit.SECONDS));

            assertTrue(operation.toCompletableFuture().cancel(true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (coordinator.isBusy(worldId)) {
                assertTrue(System.nanoTime() < deadline, "Cancelled backup did not settle");
                Thread.sleep(10);
            }
        }

        assertEquals(List.of(), regularFiles(captures));
        assertEquals(List.of(), regularFiles(archives));
        assertEquals(List.of(), catalog.listAll());
    }

    /** Every file under the root except the store's permanent per-folder operation lock. */
    private static List<Path> regularFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString()
                            .equals(ZipBackupStore.OPERATION_LOCK_NAME))
                    .toList();
        }
    }

    private static BackupMaintenanceService unusedMaintenance() {
        return (BackupMaintenanceService) Proxy.newProxyInstance(
                BackupMaintenanceService.class.getClassLoader(),
                new Class<?>[] {BackupMaintenanceService.class},
                (proxy, method, args) -> CompletableFuture.failedFuture(
                        new UnsupportedOperationException(method.getName())));
    }
}
