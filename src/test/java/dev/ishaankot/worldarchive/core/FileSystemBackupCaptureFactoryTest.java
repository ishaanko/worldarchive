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
import java.util.UUID;
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
    void removesOnlyUnlockedOwnedCrashLeftoversAndKeepsMarkersOutsidePayload() throws Exception {
        Path captures = Files.createDirectory(temporaryDirectory.resolve("captures-recovery"));
        UUID staleId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        Path staleCapture = Files.createDirectory(captureRoot(captures, staleId));
        Files.writeString(staleCapture.resolve("level.dat"), "stale", StandardCharsets.UTF_8);
        Path staleMarker = writeOwnershipMarker(captures, staleId, staleId);

        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(captures);

        assertFalse(Files.exists(staleCapture));
        assertFalse(Files.exists(staleMarker));

        UUID nextId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        Path nextCapture = Files.createDirectory(captureRoot(captures, nextId));
        Files.writeString(nextCapture.resolve("level.dat"), "next-stale", StandardCharsets.UTF_8);
        Path nextMarker = writeOwnershipMarker(captures, nextId, nextId);

        Path world = Files.createDirectory(temporaryDirectory.resolve("world-recovery"));
        Files.writeString(world.resolve("level.dat"), "current", StandardCharsets.UTF_8);
        CapturedBackup captured = factory.capture(
                request(world, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.empty(),
                CaptureProgressListener.NO_OP);
        assertFalse(Files.exists(nextCapture));
        assertFalse(Files.exists(nextMarker));
        Path activeCapture = captured.capture().worldDirectory();
        Path activeMarker = markerForCapture(activeCapture);
        try {
            assertTrue(Files.isRegularFile(activeMarker));
            try (var payload = Files.list(activeCapture)) {
                assertEquals(List.of(activeCapture.resolve("level.dat")), payload.toList());
            }
        } finally {
            captured.close();
        }
        assertFalse(Files.exists(activeCapture));
        assertFalse(Files.exists(activeMarker));
    }

    @Test
    void preservesAnActiveCaptureAcrossStartupAndNextCaptureCleanup() throws Exception {
        Path captures = temporaryDirectory.resolve("captures-active");
        Path firstWorld = Files.createDirectory(temporaryDirectory.resolve("world-active-first"));
        Files.writeString(firstWorld.resolve("level.dat"), "first", StandardCharsets.UTF_8);
        FileSystemBackupCaptureFactory firstFactory = new FileSystemBackupCaptureFactory(captures);
        CapturedBackup first = firstFactory.capture(
                request(firstWorld, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.empty(),
                CaptureProgressListener.NO_OP);
        Path firstCapture = first.capture().worldDirectory();
        Path firstMarker = markerForCapture(firstCapture);

        FileSystemBackupCaptureFactory secondFactory = new FileSystemBackupCaptureFactory(captures);
        assertTrue(Files.isDirectory(firstCapture));
        assertTrue(Files.isRegularFile(firstMarker));

        Path secondWorld = Files.createDirectory(temporaryDirectory.resolve("world-active-second"));
        Files.writeString(secondWorld.resolve("level.dat"), "second", StandardCharsets.UTF_8);
        CapturedBackup second = secondFactory.capture(
                request(secondWorld, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.empty(),
                CaptureProgressListener.NO_OP);
        try {
            assertTrue(Files.isDirectory(firstCapture));
            assertEquals("first", Files.readString(firstCapture.resolve("level.dat")));
        } finally {
            second.close();
            first.close();
        }
        assertFalse(Files.exists(firstCapture));
        assertFalse(Files.exists(firstMarker));
    }

    @Test
    void preservesUnmarkedMismatchedNoncanonicalAndUnsafeCandidates() throws Exception {
        Path captures = Files.createDirectory(temporaryDirectory.resolve("captures-conservative"));

        UUID unmarkedId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        Path unmarked = Files.createDirectory(captureRoot(captures, unmarkedId));
        Files.writeString(unmarked.resolve("keep.txt"), "unmarked", StandardCharsets.UTF_8);

        UUID mismatchedId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        Path mismatched = Files.createDirectory(captureRoot(captures, mismatchedId));
        UUID differentId = UUID.fromString("20000000-0000-0000-0000-000000000003");
        Path mismatchedMarker = writeOwnershipMarker(captures, mismatchedId, differentId);

        UUID unsafeTreeId = UUID.fromString("20000000-0000-0000-0000-000000000004");
        Path unsafeTree = Files.writeString(
                captureRoot(captures, unsafeTreeId),
                "not-a-directory",
                StandardCharsets.UTF_8);
        Path unsafeTreeMarker = writeOwnershipMarker(captures, unsafeTreeId, unsafeTreeId);

        UUID unsafeMarkerId = UUID.fromString("20000000-0000-0000-0000-000000000005");
        Path unsafeMarkerCapture = Files.createDirectory(captureRoot(captures, unsafeMarkerId));
        Path unsafeMarker = Files.createDirectory(markerForCapture(unsafeMarkerCapture));

        UUID noncanonicalId = UUID.fromString("abcdef00-0000-0000-0000-000000000006");
        Path noncanonical = Files.createDirectory(
                captures.resolve(".capture-" + noncanonicalId.toString().toUpperCase(java.util.Locale.ROOT)));
        Path noncanonicalMarker = markerForCapture(noncanonical);
        Files.writeString(
                noncanonicalMarker,
                "worldarchive-private-capture-v1:" + noncanonicalId + '\n',
                StandardCharsets.UTF_8);

        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(captures);

        assertTrue(Files.isDirectory(unmarked));
        assertTrue(Files.isDirectory(mismatched));
        assertTrue(Files.isRegularFile(mismatchedMarker));
        assertTrue(Files.isRegularFile(unsafeTree));
        assertTrue(Files.isRegularFile(unsafeTreeMarker));
        assertTrue(Files.isDirectory(unsafeMarkerCapture));
        assertTrue(Files.isDirectory(unsafeMarker));
        assertTrue(Files.isDirectory(noncanonical));
        assertTrue(Files.isRegularFile(noncanonicalMarker));

        Path world = Files.createDirectory(temporaryDirectory.resolve("world-conservative"));
        Files.writeString(world.resolve("level.dat"), "new", StandardCharsets.UTF_8);
        try (CapturedBackup ignored = factory.capture(
                request(world, BackupTrigger.MANUAL),
                BackupId.create(),
                CREATED_AT,
                Optional.empty(),
                CaptureProgressListener.NO_OP)) {
            assertTrue(Files.isDirectory(unmarked));
            assertTrue(Files.isRegularFile(unsafeTree));
        }
    }

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

    private static Path captureRoot(Path captures, UUID captureId) {
        return captures.resolve(".capture-" + captureId);
    }

    private static Path markerForCapture(Path captureRoot) {
        return captureRoot.resolveSibling(captureRoot.getFileName() + ".worldarchive-owner");
    }

    private static Path writeOwnershipMarker(Path captures, UUID fileId, UUID contentsId)
            throws IOException {
        Path marker = markerForCapture(captureRoot(captures, fileId));
        Files.writeString(
                marker,
                "worldarchive-private-capture-v1:" + contentsId + '\n',
                StandardCharsets.UTF_8);
        return marker;
    }

    private static WorldInventory.Entry entry(String path, byte[] contents) throws Exception {
        return new WorldInventory.Entry(
                path,
                contents.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)));
    }
}
