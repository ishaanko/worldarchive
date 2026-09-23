package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.assertSameFiles;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.ProgressListener;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BackupRestoreEndToEndTest {
    @TempDir
    Path root;

    private Engine engine;

    @AfterEach
    void closeEngine() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    /** Legal but unusual names, some of which look like Git's own fast-import commands, round-trip byte for byte. */
    @ParameterizedTest
    @EnumSource(DestinationType.class)
    void unusualNamesRestoreByteForByte(DestinationType destination) throws Exception {
        engine = start(Engine.onlyDestination(destination));
        TestWorld world = TestWorld.create(engine, "Odd");
        for (String name : List.of(" leading space.json", "a  b/c  d.txt", "-dash.json", "#hash.json",
                "semi;colon'quote.json", "percent%20.json", "emoji \uD83C\uDFAE/\u0444\u0430\u0439\u043B.json",
                "M 100644 inline x.json", "data/very.many.dots.dat", "unicode/\u00E9t\u00E9   sep.json")) {
            world.write(name, ("contents of " + name).getBytes(StandardCharsets.UTF_8));
        }
        Map<String, byte[]> files = world.files();

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status(), result.destinations().toString());
        assertSameFiles(files, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(result.backupId(), engine.saves, "Odd restored"), ProgressListener.NO_OP))
                .restoredWorldDirectory());
    }

    @Test
    void manualBackupRestoresIntoANewWorldAndLeavesTheSourceAlone() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Survival");
        Map<String, byte[]> backedUp = world.files();

        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        assertEquals(BackupStatus.SUCCESS, result.status());
        assertEquals(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                result.destinations().stream().map(DestinationResult::destination).sorted().toList());
        world.write("level.dat", bytes(4_096, 99));
        world.delete("playerdata/3f1a2b.dat");
        world.write("region/r.1.0.mca", bytes(1_024, 98));
        Map<String, byte[]> sourceAfterEdits = world.files();

        RestoreBackupResult restored = restore(result.backupId(), "Survival restored");

        assertEquals(engine.saves, restored.restoredWorldDirectory().getParent());
        assertNotEquals(world.path(), restored.restoredWorldDirectory());
        assertSameFiles(backedUp, restored.restoredWorldDirectory());
        assertSameFiles(sourceAfterEdits, world.path());
        assertNotEquals(world.id(), restored.restoredWorldId());
        assertEquals(
                restored.restoredWorldId(),
                engine.identities.loadExisting(restored.restoredWorldDirectory()).orElseThrow().worldId());
    }

    @Test
    void restoreFallsBackToGitWhenTheZipArchiveIsGone() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Archipelago");
        Map<String, byte[]> backedUp = world.files();
        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
        try (Stream<Path> files = Files.walk(engine.zipFolder(world.id()))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }

        RestoreBackupResult restored = restore(result.backupId(), "Archipelago from Git");

        assertSameFiles(backedUp, restored.restoredWorldDirectory());
    }

    @Test
    void everyGitSnapshotRestoresItsOwnVersion() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.GIT));
        TestWorld world = TestWorld.create(engine, "Incremental");
        Map<String, byte[]> first = world.files();
        BackupId firstId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.clock.advance(Duration.ofMinutes(5));
        world.write("region/r.0.0.mca", bytes(256 * 1_024, 7));
        Map<String, byte[]> second = world.files();
        BackupId secondId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();

        List<BackupRecord> records = engine.records(world.id()).stream()
                .sorted(Comparator.comparing(record -> record.manifest().createdAt()))
                .toList();
        assertEquals(List.of(firstId, secondId), records.stream().map(record -> record.manifest().backupId()).toList());
        assertEquals(first.size(), records.get(0).manifest().changedFileCount());
        assertEquals(1, records.get(1).manifest().changedFileCount());
        assertSameFiles(first, restore(firstId, "Incremental v1").restoredWorldDirectory());
        assertSameFiles(second, restore(secondId, "Incremental v2").restoredWorldDirectory());
    }

    @Test
    void backupsStayListedAndRestorableAfterARestart() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Persistent");
        Map<String, byte[]> backedUp = world.files();
        BackupId backupId = engine.backupNow(world, BackupTrigger.WORLD_EXIT).backupId();
        engine.close();

        engine = start(Engine.defaultConfig());

        assertEquals(List.of(backupId), engine.records(world.id()).stream()
                .map(record -> record.manifest().backupId())
                .toList());
        assertSameFiles(backedUp, restore(backupId, "Persistent restored").restoredWorldDirectory());
        assertTrue(Files.isDirectory(world.path()));
    }

    @Test
    void aLabelIsKeptExactlyAsTypedInEveryCopy() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Training");
        // The diagnostics redactor used to store this label as "Basic [REDACTED] Base".
        Optional<String> label = Optional.of("Basic Training Base");

        BackupId backupId = await(engine.backup(world, BackupTrigger.MANUAL, label)).backupId();
        Optional<String> recorded = engine.records(world.id()).getFirst().manifest().label();
        Files.delete(engine.catalogFile());
        await(engine.imports.rebuildLocal());

        BackupRecord rebuilt = engine.records(world.id()).getFirst();
        assertEquals(label, recorded);
        assertEquals(backupId, rebuilt.manifest().backupId());
        assertEquals(label, rebuilt.manifest().label());
        assertEquals(2, rebuilt.result().destinations().size());
    }

    @Test
    void aDamagedZipIsSkippedAndTheBackupRestoresFromGit() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Coral");
        Map<String, byte[]> backedUp = world.files();
        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
        flipOneByte(onlyArchive(world));

        RestoreBackupResult restored = restore(result.backupId(), "Coral from Git");

        assertSameFiles(backedUp, restored.restoredWorldDirectory());
        assertSameFiles(backedUp, world.path());
        assertNotEquals(world.id(), restored.restoredWorldId());
        assertEquals(List.of("Coral", "Coral from Git"), savesFolders());
    }

    @Test
    void whenNoCopyCanRestoreTheErrorGivesEachReasonAndNothingIsLeftInSaves() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Lost");
        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
        flipOneByte(onlyArchive(world));
        deleteTree(engine.git.repositoryFor(world.id()));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> restore(result.backupId(), "Lost"));

        String message = failure.getCause().getMessage();
        assertTrue(message.contains("ZIP: ") && message.contains("Git: "), message);
        assertEquals(List.of("Lost"), savesFolders());
    }

    @Test
    void cancellingARestoreWhileItWritesLeavesNothingInSaves() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "Undone");
        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
        CompletableFuture<CompletableFuture<RestoreBackupResult>> restore = new CompletableFuture<>();
        ProgressListener cancelWhileWriting = progress -> {
            if (progress.phase() == OperationPhase.WRITING) {
                restore.join().cancel(true);
            }
        };

        restore.complete(engine.recovery.restoreBackup(
                new RestoreBackupRequest(result.backupId(), engine.saves, "Undone restored"), cancelWhileWriting)
                .toCompletableFuture());

        assertThrows(CancellationException.class, () -> Engine.await(restore.join()));
        assertEquals(List.of("Undone"), savesFolders());
    }

    @Test
    void aRestoreCannotBeCancelledOnceItsWorldIsPublished() throws Exception {
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "Kept");
        Map<String, byte[]> backedUp = world.files();
        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);
        CompletableFuture<CompletableFuture<RestoreBackupResult>> restore = new CompletableFuture<>();
        AtomicBoolean refused = new AtomicBoolean();
        ProgressListener cancelOncePublished = progress -> {
            if (progress.phase() == OperationPhase.COMPLETE) {
                refused.set(!restore.join().cancel(true));
            }
        };

        restore.complete(engine.recovery.restoreBackup(
                new RestoreBackupRequest(result.backupId(), engine.saves, "Kept restored"), cancelOncePublished)
                .toCompletableFuture());

        RestoreBackupResult restored = Engine.await(restore.join());
        assertTrue(refused.get());
        assertSameFiles(backedUp, restored.restoredWorldDirectory());
    }

    @Test
    void aSavesFolderReachedThroughALinkReceivesTheRestoredWorldWhereItReallyIs() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"), "links need extra rights on Windows");
        Path realSaves = Files.createDirectories(root.resolve("shared-saves"));
        Files.createSymbolicLink(root.resolve("saves"), realSaves);
        engine = start(Engine.onlyDestination(DestinationType.ZIP));
        TestWorld world = TestWorld.create(engine, "Shared");
        Map<String, byte[]> backedUp = world.files();
        BackupResult result = engine.backupNow(world, BackupTrigger.MANUAL);

        RestoreBackupResult restored = restore(result.backupId(), "Shared restored");

        assertEquals(realSaves.toRealPath().resolve("Shared restored"), restored.restoredWorldDirectory());
        assertSameFiles(backedUp, engine.saves.resolve("Shared restored"));
    }

    private Path onlyArchive(TestWorld world) throws IOException {
        try (Stream<Path> files = Files.walk(engine.zipFolder(world.id()))) {
            List<Path> archives = files.filter(file -> file.toString().endsWith(".zip")).toList();
            assertEquals(1, archives.size(), archives.toString());
            return archives.getFirst();
        }
    }

    private static void flipOneByte(Path archive) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
            long middle = file.length() / 2;
            file.seek(middle);
            int value = file.read();
            file.seek(middle);
            file.write(value ^ 0xFF);
        }
    }

    /** The names in saves, sorted; a staging folder left behind would show up here too. */
    private List<String> savesFolders() throws IOException {
        try (Stream<Path> folders = Files.list(engine.saves)) {
            return folders.map(folder -> folder.getFileName().toString()).sorted().toList();
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private Engine start(WorldArchiveConfig config) {
        return new Engine(
                root,
                new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")),
                config,
                SourceCaptureObserver.NONE);
    }

    private RestoreBackupResult restore(BackupId backupId, String name) throws Exception {
        return await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(backupId, engine.saves, name),
                ProgressListener.NO_OP));
    }
}
