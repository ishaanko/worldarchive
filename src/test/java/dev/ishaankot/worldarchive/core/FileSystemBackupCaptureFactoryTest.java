package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSystemBackupCaptureFactoryTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-17T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsOneImmutablePortableCaptureWithExactChangeAccounting() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world space"));
        Files.writeString(world.resolve("level.dat"), "new-level", StandardCharsets.UTF_8);
        Path region = Files.createDirectory(world.resolve("région"));
        Files.write(region.resolve("r.0.0.mca"), new byte[] {1, 2, 3, 4});
        Path internal = Files.createDirectory(world.resolve(".worldarchive"));
        Files.writeString(internal.resolve("world.json"), "identity", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("session.lock"), "lock", StandardCharsets.UTF_8);
        WorldInventory previous = WorldInventory.create(List.of(
                entry("deleted.dat", "gone".getBytes(StandardCharsets.UTF_8)),
                entry("level.dat", "old-level".getBytes(StandardCharsets.UTF_8))));
        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(
                temporaryDirectory.resolve("captures"));

        CapturedBackup captured = factory.capture(
                request(world, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.of(previous),
                CaptureProgressListener.NO_OP);
        Path privateRoot = captured.capture().worldDirectory();
        try {
            assertFalse(privateRoot.startsWith(world));
            assertEquals(2, captured.inventory().fileCount());
            assertEquals(3, captured.capture().manifest().changedFileCount());
            assertFalse(Files.exists(privateRoot.resolve(".worldarchive")));
            assertFalse(Files.exists(privateRoot.resolve("session.lock")));
            assertArrayEquals(
                    new byte[] {1, 2, 3, 4},
                    Files.readAllBytes(privateRoot.resolve("région").resolve("r.0.0.mca")));

            Files.writeString(world.resolve("level.dat"), "mutated-live", StandardCharsets.UTF_8);
            assertEquals("new-level", Files.readString(privateRoot.resolve("level.dat")));
        } finally {
            captured.close();
        }
        assertFalse(Files.exists(privateRoot));
        assertEquals("identity", Files.readString(internal.resolve("world.json")));
    }

    @Test
    void detectsSourceMutationAndDeletesPrivateStaging() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "before", StandardCharsets.UTF_8);
        Path captures = temporaryDirectory.resolve("captures");
        SourceCaptureObserver mutator = new SourceCaptureObserver() {
            @Override
            public void afterFileCopy(Path relativePath) throws IOException {
                Files.writeString(world.resolve(relativePath), "after", StandardCharsets.UTF_8);
            }
        };
        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(captures, mutator);

        assertThrows(IOException.class, () -> factory.capture(
                request(world, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.empty(),
                CaptureProgressListener.NO_OP));

        assertTrue(Files.isDirectory(captures));
        try (var entries = Files.list(captures)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void interruptionCleansCaptureBeforeAnyConsumerCanReceiveIt() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "contents", StandardCharsets.UTF_8);
        Path captures = temporaryDirectory.resolve("captures");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SourceCaptureObserver blocker = new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws InterruptedException {
                entered.countDown();
                release.await();
            }
        };
        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(captures, blocker);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CapturedBackup> capture = executor.submit(() -> factory.capture(
                    request(world, BackupTrigger.MANUAL),
                    BackupId.create(),
                    CREATED_AT,
                    Optional.empty(),
                    CaptureProgressListener.NO_OP));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            capture.cancel(true);
            release.countDown();
            assertThrows(java.util.concurrent.CancellationException.class, capture::get);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        try (var entries = Files.list(captures)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void rejectsCaptureDirectoryInsideLiveWorld() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "contents", StandardCharsets.UTF_8);
        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(
                world.resolve("captures"));

        assertThrows(IOException.class, () -> factory.capture(
                request(world, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.empty(),
                CaptureProgressListener.NO_OP));
    }

    @Test
    void canonicalReadOnlyCaptureIsAcceptedByZipDestination() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world-zip"));
        Files.writeString(world.resolve("level.dat"), "contents", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("region").resolve("r.0.0.mca"), new byte[] {4, 3, 2, 1});
        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(
                temporaryDirectory.resolve("captures-zip"));
        CapturedBackup captured = factory.capture(
                request(world, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.empty(),
                CaptureProgressListener.NO_OP);
        try {
            ZipBackupStore store = new ZipBackupStore(temporaryDirectory.resolve("archives"));
            ZipBackupArtifact artifact = store.create(captured.capture());

            assertTrue(store.verify(artifact.archivePath()).valid());
            assertEquals(captured.capture().manifest(), artifact.manifest());
        } finally {
            captured.close();
        }
    }

    private static CreateBackupRequest request(Path world, BackupTrigger trigger) {
        return new CreateBackupRequest(
                WorldId.create(),
                world,
                "Test World",
                trigger);
    }

    private static WorldInventory.Entry entry(String path, byte[] contents) throws Exception {
        return new WorldInventory.Entry(
                path,
                contents.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)));
    }
}
