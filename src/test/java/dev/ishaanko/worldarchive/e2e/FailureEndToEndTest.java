package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.assertSameFiles;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.DestinationTriggerConfig;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.ZipDestinationConfig;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FailureEndToEndTest {
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
    void aFailingZipFolderStillKeepsTheGitCopy() throws Exception {
        Path notAFolder = Files.writeString(root.resolve("zip-folder-is-a-file"), "x");
        engine = start(withFolders(Optional.empty(), Optional.of(notAFolder)), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Half");
        Map<String, byte[]> backedUp = world.files();

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(DestinationStatus.SUCCESS, destination(result, DestinationType.GIT).status());
        assertEquals(DestinationStatus.FAILED, destination(result, DestinationType.ZIP).status());
        assertEquals(1, engine.records(world.id()).size());
        assertSameFiles(backedUp, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(result.backupId(), engine.saves, "Half restored"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    /** The world list was loaded, then the world folder was deleted: the backup fails and names the folder. */
    @Test
    void aWorldDeletedBeforeItsBackupFailsWithoutARecordOrCopies() throws Exception {
        engine = start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Deleted");
        try (Stream<Path> files = Files.walk(world.path())) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }

        ExecutionException failure = assertThrows(ExecutionException.class, () -> engine.backupNow(world, BackupTrigger.MANUAL));

        assertTrue(failure.getCause().getMessage().contains(world.path().toString()), failure.getCause().getMessage());
        assertTrue(engine.records(world.id()).isEmpty());
        assertNoWorldCopies(engine.storage.resolve("capture-temp"));
    }

    @Test
    void aBackupWithNoWorkingDestinationRecordsNothingAndLeavesNoCopies() throws Exception {
        Path notAFolder = Files.writeString(root.resolve("not-a-folder"), "x");
        engine = start(withFolders(Optional.of(notAFolder), Optional.of(notAFolder)), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Nowhere");

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.FAILED, result.status());
        assertTrue(engine.records(world.id()).isEmpty());
        assertNoWorldCopies(engine.storage.resolve("capture-temp"));
    }

    /**
     * The drive of the folders the player chose is away, but its mount point is there. The backup
     * fails, and neither folder is created on the computer's own disk in the drive's place.
     */
    @Test
    void backupsToChosenFoldersThatAreMissingFailAndCreateNothing() throws Exception {
        Path mountPoint = Files.createDirectories(root.resolve("usb"));
        engine = start(withFolders(Optional.of(mountPoint.resolve("Git")), Optional.of(mountPoint.resolve("Zips"))),
                SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Away");

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.FAILED, result.status(), result.destinations().toString());
        for (DestinationType type : DestinationType.values()) {
            String reason = destination(result, type).message().orElseThrow();
            assertTrue(reason.contains("cannot be reached"), reason);
        }
        try (Stream<Path> created = Files.list(mountPoint)) {
            assertEquals(List.of(), created.toList());
        }
    }

    @Test
    void cancellingDuringCaptureLeavesNoBackupBehind() throws Exception {
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger copies = new AtomicInteger();
        SourceCaptureObserver blockOnSecondFile = new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws InterruptedException {
                if (copies.incrementAndGet() == 2) {
                    copying.countDown();
                    never.await();
                }
            }
        };
        engine = start(Engine.defaultConfig(), blockOnSecondFile);
        TestWorld world = TestWorld.create(engine, "Cancelled");

        CompletableFuture<BackupResult> backup = engine.backup(
                world, BackupTrigger.MANUAL, Optional.empty()).toCompletableFuture();
        assertTrue(copying.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        assertTrue(backup.cancel(true));

        awaitIdle(world);
        assertThrows(CancellationException.class, backup::join);
        assertTrue(engine.records(world.id()).isEmpty());
        assertNoWorldCopies(engine.storage);
    }

    @Test
    void aWorldThatChangesDuringCaptureIsCapturedConsistently() throws Exception {
        AtomicBoolean changed = new AtomicBoolean();
        TestWorld[] target = new TestWorld[1];
        SourceCaptureObserver writeBehindTheCopy = new SourceCaptureObserver() {
            @Override
            public void afterFileCopy(Path relativePath) throws IOException {
                if (relativePath.toString().equals("empty.txt") && changed.compareAndSet(false, true)) {
                    target[0].write("empty.txt", bytes(512, 11));
                }
            }
        };
        engine = start(Engine.defaultConfig(), writeBehindTheCopy);
        target[0] = TestWorld.create(engine, "Busy");

        BackupResult result = engine.backupNow(target[0], BackupTrigger.MANUAL);

        assertTrue(changed.get());
        assertEquals(BackupStatus.SUCCESS, result.status());
        assertSameFiles(target[0].files(), await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(result.backupId(), engine.saves, "Busy restored"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void aSaveThatReplacesLevelDatDuringTheCaptureIsRetriedAndCaptured() throws Exception {
        TestWorld[] target = new TestWorld[1];
        AtomicBoolean saved = new AtomicBoolean();
        SourceCaptureObserver saveDuringCapture = new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws IOException {
                // The game writes level.dat_new, then moves it over level.dat; the listed temporary file vanishes.
                if (relativePath.toString().equals("level.dat_new") && saved.compareAndSet(false, true)) {
                    Files.move(
                            target[0].path().resolve("level.dat_new"),
                            target[0].path().resolve("level.dat"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        };
        engine = start(Engine.defaultConfig(), saveDuringCapture);
        target[0] = TestWorld.create(engine, "Saving");
        target[0].write("level.dat_new", bytes(4_096, 21));
        Map<String, byte[]> expected = new java.util.TreeMap<>(target[0].files());
        expected.put("level.dat", expected.remove("level.dat_new"));

        BackupResult result = engine.backupNow(target[0], BackupTrigger.MANUAL);

        assertTrue(saved.get());
        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        assertSameFiles(expected, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(result.backupId(), engine.saves, "Saving restored"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void aBackupThatCannotBeListedSaysSoAndIsListedAfterTheRestartRebuild() throws Exception {
        engine = start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Unlisted");
        Map<String, byte[]> backedUp = world.files();
        Files.createDirectories(engine.catalogFile());

        ExecutionException failure = assertThrows(
                ExecutionException.class, () -> engine.backupNow(world, BackupTrigger.MANUAL));
        Files.delete(engine.catalogFile());
        await(engine.imports.rebuildLocal());

        assertTrue(failure.getCause().getMessage().contains("could not add it to its backup list"),
                failure.getCause().getMessage());
        assertEquals(1, engine.records(world.id()).size());
        assertSameFiles(backedUp, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(engine.records(world.id()).getFirst().manifest().backupId(),
                        engine.saves, "Unlisted restored"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void aDamagedZipFailsVerificationAndIsNeverRestored() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Damaged");
        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
        Path archive = onlyFile(engine.zipFolder(world.id()), ".zip");
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
            long middle = file.length() / 2;
            file.seek(middle);
            int value = file.read();
            file.seek(middle);
            file.write(value ^ 0xFF);
        }

        BackupResult verified = await(engine.recovery.verifyBackup(result.backupId(), ProgressListener.NO_OP));
        ExecutionException restore = assertThrows(ExecutionException.class, () -> await(
                engine.recovery.restoreBackup(
                        new RestoreBackupRequest(result.backupId(), engine.saves, "Damaged restored"),
                        ProgressListener.NO_OP)));

        assertEquals(VerificationStatus.FAILED, destination(verified, DestinationType.ZIP).verificationStatus());
        assertInstanceOf(RuntimeException.class, restore.getCause());
        try (Stream<Path> saves = Files.list(engine.saves)) {
            assertEquals(List.of(world.path()), saves.toList());
        }
    }

    @Test
    void filesLeftByACrashAreNotListedAndDoNotBlockTheNextBackup() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Crashed");
        engine.backupNow(world, BackupTrigger.MANUAL);
        Path archive = onlyFile(engine.zipFolder(world.id()), ".zip");
        Path folder = archive.getParent();
        byte[] complete = Files.readAllBytes(archive);
        Files.write(folder.resolve("half-written.zip.partial"), Arrays.copyOf(complete, complete.length / 2));
        Files.createDirectories(engine.storage.resolve("capture-temp").resolve("stale-capture"));
        engine.close();
        Files.delete(engine.storage.resolve("catalog.json"));
        engine = start(Engine.onlyDestination(DestinationType.ZIP), SourceCaptureObserver.NONE);

        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 12));
        BackupResult next = engine.backupNow(world, BackupTrigger.MANUAL);
        await(engine.imports.rebuildLocal());

        assertEquals(BackupStatus.SUCCESS, next.status());
        assertEquals(2, engine.records(world.id()).size());
    }

    @Test
    void cancellingDuringAStalledPushKeepsTheCopiesThatFinished() throws Exception {
        Path remote = root.resolve("stalled.git");
        Files.createDirectories(remote);
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        Path pushing = root.resolve("push-started");
        Path hook = remote.resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + pushing + "'\nsleep 60\n");
        hook.toFile().setExecutable(true);
        TestWorld world = TestWorld.create(root.resolve("saves"), "Stalled");
        engine = start(Engine.defaultConfig(Engine.world(
                world.id(), world.path(), Optional.of(remote.toString()),
                dev.ishaanko.worldarchive.config.StoragePolicy.defaults())), SourceCaptureObserver.NONE);

        CompletableFuture<BackupResult> backup = engine.backup(
                world, BackupTrigger.MANUAL, Optional.empty()).toCompletableFuture();
        long deadline = System.nanoTime() + Engine.TIMEOUT.toNanos();
        while (!Files.exists(pushing) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(pushing), "the push never reached the remote");
        assertTrue(backup.cancel(true));
        awaitIdle(world);

        List<dev.ishaanko.worldarchive.model.BackupRecord> records = engine.records(world.id());
        assertEquals(1, records.size());
        assertEquals(DestinationStatus.SUCCESS, destination(records.getFirst().result(), DestinationType.ZIP).status());
        assertEquals(
                DestinationStatus.PENDING_SYNC,
                destination(records.getFirst().result(), DestinationType.GIT).status());
    }

    @Test
    void aStaleLooseObjectInTheRepositoryDoesNotBlockTheNextBackup() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.GIT), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "PowerLoss");
        engine.backupNow(world, BackupTrigger.MANUAL);
        Path objects;
        try (Stream<Path> found = Files.walk(engine.storage)) {
            objects = found.filter(path -> path.getFileName().toString().equals("objects")
                            && Files.exists(path.resolveSibling("HEAD")))
                    .findFirst()
                    .orElseThrow();
        }
        Path emptyObject = objects.resolve("ab").resolve("cdef0123456789abcdef0123456789abcdef01");
        Files.createDirectories(emptyObject.getParent());
        Files.write(emptyObject, new byte[0]);
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 13));

        BackupResult next = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, next.status(), next.destinations().toString());
    }

    @Test
    void aNestedGitRepositoryInAWorldDoesNotBreakBackups() throws Exception {
        engine = start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Datapack Dev");
        world.write("datapacks/mypack/pack.mcmeta", "{\"pack\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        world.write("datapacks/mypack/.git/HEAD", "ref: refs/heads/main\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, byte[]> expected = new java.util.TreeMap<>(world.files());
        expected.remove("datapacks/mypack/.git/HEAD");

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        assertSameFiles(expected, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(result.backupId(), engine.saves, "Datapack Dev restored"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void partialFilesLeftByACrashAreSweptByTheNextBackup() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP), SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Swept");
        engine.backupNow(world, BackupTrigger.MANUAL);
        Path folder = onlyFile(engine.zipFolder(world.id()), ".zip").getParent();
        Path leftover = folder.resolve("2026-09-01_11-00-00Z - Swept - Manual - "
                + dev.ishaanko.worldarchive.model.BackupId.create() + ".zip.partial");
        Files.write(leftover, bytes(1_024, 14));
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 15));

        engine.backupNow(world, BackupTrigger.MANUAL);

        assertFalse(Files.exists(leftover));
    }

    private void awaitIdle(TestWorld world) throws InterruptedException {
        long deadline = System.nanoTime() + Engine.TIMEOUT.toNanos();
        while (engine.coordinator.isBusy(world.id())) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Backup did not stop after cancellation");
            }
            Thread.sleep(10);
        }
    }

    private Engine start(WorldArchiveConfig config, SourceCaptureObserver observer) {
        return new Engine(root, new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")), config, observer);
    }

    private static WorldArchiveConfig withFolders(Optional<Path> gitRepository, Optional<Path> zipFolder) {
        WorldArchiveConfig defaults = Engine.defaultConfig();
        GitDestinationConfig git = defaults.git();
        return new WorldArchiveConfig(
                defaults.triggers(),
                new GitDestinationConfig(
                        true,
                        gitRepository,
                        git.remoteName(),
                        DestinationTriggerConfig.defaults(),
                        git.lfsPatterns()),
                new ZipDestinationConfig(
                        true,
                        zipFolder,
                        DestinationTriggerConfig.defaults()),
                List.of());
    }

    private static DestinationResult destination(BackupResult result, DestinationType type) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == type)
                .findFirst()
                .orElseThrow();
    }

    private static Path onlyFile(Path folder, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            List<Path> matches = files.filter(file -> file.toString().endsWith(suffix)).toList();
            assertEquals(1, matches.size(), matches.toString());
            return matches.getFirst();
        }
    }

    /** No private world copy (staging folders, archives, partial files) remains under the folder. */
    private static void assertNoWorldCopies(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            List<Path> copies = files
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.equals("level.dat") || name.endsWith(".mca") || name.contains(".zip");
                    })
                    .toList();
            assertFalse(copies.stream().findAny().isPresent(), copies.toString());
        }
    }
}
