package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.assertSameFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.ui.model.BackupOutcomeSummary;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ZIP backups through the real engine: deletes that fail, cancels, lost checksum files, and missing folders. */
class ZipEndToEndTest {
    @TempDir
    Path root;

    private Engine engine;

    @AfterEach
    void closeEngine() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void aReadOnlyZipFolderFailsTheDeleteAndKeepsTheBackup() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "Locked");
        Map<String, byte[]> backedUp = world.files();
        BackupResult backup = engine.backupNow(world, BackupTrigger.MANUAL);
        Path folder = onlyArchive(world).getParent();
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        BackupResult deleted;
        try {
            Assumptions.assumeFalse(Files.isWritable(folder), "permissions do not apply to this user");
            deleted = engine.delete(backup.backupId(), ProgressListener.NO_OP);
        } finally {
            Files.setPosixFilePermissions(folder, writable);
        }

        assertEquals(DestinationStatus.FAILED, destination(deleted).status());
        assertEquals(1, engine.records(world.id()).size());
        assertSameFiles(backedUp, restore(backup, "Locked restored"));
    }

    @Test
    void cancellingWhileTheZipIsWrittenLeavesNothingBehind() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "Cancelled");
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<CompletableFuture<BackupResult>> backup = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressListener cancelWhileWriting = progress -> {
            if (progress.phase() == OperationPhase.WRITING
                    && progress.completedUnits() > 0
                    && cancelled.compareAndSet(false, true)) {
                backup.join().cancel(true);
                started.countDown();
            }
        };

        backup.complete(engine.coordinator.createBackup(
                new CreateBackupRequest(world.id(), world.path(), world.name(), Optional.empty(), BackupTrigger.MANUAL),
                cancelWhileWriting).toCompletableFuture());

        assertTrue(started.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        awaitIdle(world);
        assertThrows(CancellationException.class, () -> backup.join().join());
        assertEquals(List.of(), engine.records(world.id()));
        assertEquals(List.of(), filesUnder(engine.zipFolder(world.id())));
    }

    @Test
    void aZipBackupThatLostItsChecksumFileStillVerifiesAndRestores() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "Bare");
        Map<String, byte[]> backedUp = world.files();
        BackupResult backup = engine.backupNow(world, BackupTrigger.MANUAL);
        Path archive = onlyArchive(world);
        Files.delete(archive.resolveSibling(archive.getFileName() + ".sha256"));

        BackupResult verified = await(engine.recovery.verifyBackup(backup.backupId(), ProgressListener.NO_OP));

        assertEquals(VerificationStatus.VERIFIED, destination(verified).verificationStatus());
        List<String> shown = BackupOutcomeSummary.from(BackupOperation.VERIFY, verified).lines();
        assertTrue(shown.getFirst().contains("checksum file (.sha256)"), "Verify tells the player: " + shown);
        assertSameFiles(backedUp, restore(backup, "Bare restored"));
    }

    @Test
    void aZipFolderThatCannotBeReachedIsNamedInTheFailure() throws Exception {
        Path blocked = Files.writeString(root.resolve("unplugged"), "a file where a drive would be");
        Path folder = blocked.resolve("Backups");
        WorldArchiveConfig zipOnly = Engine.onlyDestination(DestinationType.ZIP);
        engine = start(zipOnly.withZip(zipOnly.zip().withDestination(Optional.of(folder))));
        TestWorld world = TestWorld.create(engine, "Unplugged");

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.FAILED, result.status());
        String message = destination(result).message().orElseThrow();
        assertTrue(message.contains(folder.toString()) && message.contains("cannot be created or reached"), message);
    }

    private Engine start(WorldArchiveConfig config) {
        return new Engine(root, new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")), config,
                SourceCaptureObserver.NONE);
    }

    private Path restore(BackupResult backup, String name) throws Exception {
        return await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(backup.backupId(), engine.saves, name),
                ProgressListener.NO_OP)).restoredWorldDirectory();
    }

    private Path onlyArchive(TestWorld world) throws IOException {
        List<Path> archives = filesUnder(engine.zipFolder(world.id())).stream()
                .filter(file -> file.getFileName().toString().endsWith(".zip"))
                .toList();
        assertEquals(1, archives.size(), archives.toString());
        return archives.getFirst();
    }

    private void awaitIdle(TestWorld world) throws InterruptedException {
        long deadline = System.nanoTime() + Engine.TIMEOUT.toNanos();
        while (engine.coordinator.isBusy(world.id())) {
            assertTrue(System.nanoTime() < deadline, "the backup did not stop after the cancel");
            Thread.sleep(10);
        }
    }

    private static DestinationResult destination(BackupResult result) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.ZIP)
                .findFirst()
                .orElseThrow();
    }

    /** Every file under the folder except the lock file each world folder keeps. */
    private static List<Path> filesUnder(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().equals(".worldarchive.lock"))
                    .toList();
        }
    }
}
