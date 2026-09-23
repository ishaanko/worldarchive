package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.runtime.BackgroundReports;
import dev.ishaanko.worldarchive.runtime.LiveServer;
import dev.ishaanko.worldarchive.runtime.LiveWorldBackups;
import dev.ishaanko.worldarchive.runtime.Notice;
import dev.ishaanko.worldarchive.runtime.WorldIdentityResolver;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The open world's backups, driven by a fake integrated server against the real engine: the save
 * that comes first, the world-exit backup, cancel, a server that vanished, and the schedule.
 */
class LiveWorldBackupsTest {
    private static final Duration SCHEDULE = Duration.ofMinutes(30);

    @TempDir
    Path root;

    private final AtomicLong nanos = new AtomicLong();

    private final Reports reports = new Reports();

    private final CountDownLatch copying = new CountDownLatch(1);

    private final CountDownLatch release = new CountDownLatch(1);

    private Engine engine;

    private WorldIdentityResolver resolver;

    private LiveWorldBackups live;

    @AfterEach
    void close() throws Exception {
        release.countDown();
        if (live != null) {
            live.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    /** The player turned autosave off with /save-off: a backup pauses it too, and leaves it off. */
    @Test
    void aBackupOfTheOpenWorldSavesItFirstAndLeavesAutosaveAsThePlayerHadIt() throws Exception {
        start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        FakeServer server = new FakeServer(TestWorld.create(engine, "Open"));
        server.autoSave = false;
        BackupWorldContext world = open(server);

        CompletableFuture<BackupResult> backup = backUp(world);
        server.runTasks();
        BackupResult result = await(backup);
        awaitIdle();
        server.runTasks();

        assertEquals(BackupStatus.SUCCESS, result.status());
        assertEquals(1, server.saves);
        assertEquals(List.of(false, false), server.autoSaveChanges);
        TestWorld.assertSameFiles(server.world.files(), restore(result.backupId()));
    }

    /**
     * A second backup is requested just as the first one hands autosave back to the server. It is
     * refused until autosave is back, so it never takes the paused autosave for the player's own.
     */
    @Test
    void aBackupRequestedWhileAutosaveComesBackLeavesAutosaveAsThePlayerHadIt() throws Exception {
        start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        FakeServer server = new FakeServer(TestWorld.create(engine, "Race"));
        BackupWorldContext world = open(server);
        CompletableFuture<BackupResult> first = backUp(world);
        AtomicReference<CompletableFuture<BackupResult>> second = new AtomicReference<>();
        // The first task a worker hands to the server gives autosave back; the request lands right before it.
        server.beforeNextWorkerTask = () -> second.set(backUp(world));

        server.runTasks();
        assertEquals(BackupStatus.SUCCESS, await(first).status());
        server.runTasks();
        CompletableFuture<BackupResult> third = backUp(world);
        server.runTasks();
        assertEquals(BackupStatus.SUCCESS, await(third).status());
        awaitIdle();
        server.runTasks();

        assertEquals(List.of(false, true, false, true), server.autoSaveChanges);
        ExecutionException refused = assertThrows(ExecutionException.class, () -> await(second.get()));
        assertTrue(refused.getCause().getMessage().contains("still copying"), refused.getCause().getMessage());
    }

    @Test
    void theWorldExitBackupCopiesTheWorldAfterItsFinalSave() throws Exception {
        start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        FakeServer server = new FakeServer(TestWorld.create(engine, "Leaving"));
        open(server);

        stop(server, true);

        assertEquals("screen.worldarchive.notice.exit_done", reports.exitOutcome.get(30, TimeUnit.SECONDS).key());
        awaitIdle();
        BackupRecord exit = onlyRecord(server.world.id());
        assertEquals(BackupTrigger.WORLD_EXIT, exit.manifest().trigger());
        TestWorld.assertSameFiles(server.world.files(), restore(exit.manifest().backupId()));
    }

    @Test
    void aStopWithoutTheFinalSaveMakesNoBackupAndSaysTheWorldWasNotSaved() throws Exception {
        start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        FakeServer server = new FakeServer(TestWorld.create(engine, "Crashed"));
        open(server);

        stop(server, false);

        assertEquals("screen.worldarchive.notice.save_missing", reports.notMade.get(30, TimeUnit.SECONDS).key());
        assertTrue(engine.records(server.world.id()).isEmpty());
        assertEquals(0, reports.started.size());
    }

    /** Quitting while a manual backup copies the world: the exit backup waits for that copy and records the final save. */
    @Test
    void quittingWhileABackupCopiesTheWorldStillBacksUpItsFinalState() throws Exception {
        start(Engine.defaultConfig(), holdFirstCapture());
        FakeServer server = new FakeServer(TestWorld.create(engine, "Quitting"));
        BackupWorldContext world = open(server);
        CompletableFuture<BackupResult> manual = backUp(world);
        server.runTasks();
        assertTrue(copying.await(30, TimeUnit.SECONDS));

        stop(server, true);
        release.countDown();

        assertEquals(BackupStatus.SUCCESS, await(manual).status());
        assertEquals("screen.worldarchive.notice.exit_done", reports.exitOutcome.get(30, TimeUnit.SECONDS).key());
        awaitIdle();
        BackupRecord exit = engine.records(server.world.id()).stream()
                .filter(record -> record.manifest().trigger() == BackupTrigger.WORLD_EXIT)
                .findFirst()
                .orElseThrow();
        TestWorld.assertSameFiles(server.world.files(), restore(exit.manifest().backupId()));
    }

    /**
     * The settings bind the world's identity to its old folder. The destination selector refuses
     * the world, which once threw out of the stopping event and skipped the game's final save.
     */
    @Test
    void aWorldTheSettingsPlaceElsewhereClosesNormallyAndItsExitBackupSaysWhy() throws Exception {
        TestWorld moved = TestWorld.create(root.resolve("saves"), "Moved");
        start(Engine.defaultConfig(new WorldConfig(
                        moved.id(), true, root.resolve("saves/Old Name"), Optional.empty(), Optional.empty(),
                        StoragePolicy.defaults())),
                SourceCaptureObserver.NONE);
        FakeServer server = new FakeServer(moved);
        open(server);

        stop(server, true);

        Notice notMade = reports.notMade.get(30, TimeUnit.SECONDS);
        assertEquals("screen.worldarchive.notice.exit_not_made", notMade.key());
        assertTrue(notMade.arguments().getFirst().contains("another folder"), notMade.arguments().toString());
        assertTrue(engine.records(moved.id()).isEmpty());
    }

    /** A server that stopped without reporting it leaves its save behind; the next world must not inherit it. */
    @Test
    void theNextWorldBacksUpAfterAServerThatNeverReportedItsStop() throws Exception {
        start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        FakeServer vanished = new FakeServer(TestWorld.create(engine, "Vanished"));
        CompletableFuture<BackupResult> stuck = backUp(open(vanished));

        FakeServer next = new FakeServer(TestWorld.create(engine, "Next"));
        BackupWorldContext world = open(next);

        assertThrows(ExecutionException.class, () -> await(stuck));
        CompletableFuture<BackupResult> backup = backUp(world);
        next.runTasks();
        assertEquals(BackupStatus.SUCCESS, await(backup).status());
        awaitIdle();
    }

    @Test
    void aCancelDuringTheCopyLeavesNoBackupAndHandsBackTheWorld() throws Exception {
        start(Engine.defaultConfig(), holdFirstCapture());
        FakeServer server = new FakeServer(TestWorld.create(engine, "Cancelled"));
        CompletableFuture<BackupResult> backup = backUp(open(server));
        server.runTasks();
        assertTrue(copying.await(30, TimeUnit.SECONDS));

        assertTrue(backup.cancel(true));

        assertTrue(backup.isCancelled());
        awaitIdle();
        server.runTasks();
        assertEquals(List.of(false, true), server.autoSaveChanges);
        assertTrue(engine.records(server.world.id()).isEmpty());
        assertNoWorldCopies(engine.storage.resolve("capture-temp"));
    }

    /**
     * A paused game does not tick, so nothing changed: the schedule skips it without saving or a
     * word. Once the server ticked, the next run backs up, although only the save rewrote level.dat.
     */
    @Test
    void theScheduleSkipsAGameThatDidNotTickSinceTheLastBackup() throws Exception {
        start(Engine.defaultConfig(), SourceCaptureObserver.NONE);
        FakeServer server = new FakeServer(TestWorld.create(engine, "Paused"));
        open(server);

        runSchedule(server);
        assertEquals(1, engine.records(server.world.id()).size());
        runSchedule(server);
        assertEquals(1, server.saves);
        server.ticks += 20;
        runSchedule(server);

        assertEquals(2, engine.records(server.world.id()).size());
        assertEquals(2, server.saves);
        assertEquals(List.of(), reports.warnings);
        assertTrue(server.autoSave);
    }

    @Test
    void theScheduleSkipsAWorldThePlayerTurnedOffWithoutAWarning() throws Exception {
        TestWorld off = TestWorld.create(root.resolve("saves"), "Off");
        start(Engine.defaultConfig(new WorldConfig(
                        off.id(), false, off.path(), Optional.empty(), Optional.empty(), StoragePolicy.defaults())),
                SourceCaptureObserver.NONE);
        FakeServer server = new FakeServer(off);
        open(server);

        server.ticks += 20;
        runSchedule(server);

        assertEquals(0, server.saves);
        assertEquals(List.of(), reports.warnings);
        assertTrue(engine.records(off.id()).isEmpty());
    }

    private void start(WorldArchiveConfig config, SourceCaptureObserver observer) {
        engine = new Engine(root, new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")), config, observer);
        resolver = new WorldIdentityResolver(engine.saves, engine.identities, new WorldIdentityResolver.Listener() {
            @Override
            public void found(WorldId worldId, Path worldDirectory) {
            }

            @Override
            public void copyFound(Path copy) {
            }
        });
        resolver.configure(engine.graph.current().orElseThrow().config());
        live = new LiveWorldBackups(engine.graph, resolver, reports, nanos::get);
    }

    /** Starts the server's world and waits until WorldArchive knows its identity. */
    private BackupWorldContext open(FakeServer server) throws InterruptedException {
        live.started(server);
        long deadline = System.nanoTime() + Engine.TIMEOUT.toNanos();
        while (live.liveWorld().isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the open world was never resolved");
            live.tick();
            Thread.sleep(5);
        }
        return live.liveWorld().orElseThrow();
    }

    /** Stops the server the way the game does: stopping, the final save when it happens, stopped. */
    private void stop(FakeServer server, boolean finalSave) throws IOException {
        live.stopping(server);
        if (finalSave) {
            server.world.write("level.dat", bytes(4_096, 999));
            live.saved(server, true, false);
        }
        live.stopped(server);
    }

    private CompletableFuture<BackupResult> backUp(BackupWorldContext world) {
        CreateBackupRequest request = new CreateBackupRequest(
                world.worldId(), world.worldDirectory(), world.displayName(), Optional.empty(), BackupTrigger.MANUAL);
        return live.backUpOpenWorld(
                engine.graph.current().orElseThrow(), request, ProgressListener.NO_OP, engine.graph.gate().enterWork());
    }

    /** Lets one schedule interval pass, runs what the tick started on the server thread, and waits for it. */
    private void runSchedule(FakeServer server) throws InterruptedException {
        live.tick();
        nanos.addAndGet(SCHEDULE.toNanos());
        live.tick();
        server.runTasks();
        awaitIdle();
        server.runTasks();
    }

    private void awaitIdle() throws InterruptedException {
        assertTrue(engine.graph.gate().awaitIdle(Engine.TIMEOUT), "backup work did not finish");
    }

    private BackupRecord onlyRecord(WorldId worldId) throws IOException {
        List<BackupRecord> records = engine.records(worldId);
        assertEquals(1, records.size(), records.toString());
        return records.getFirst();
    }

    private Path restore(BackupId backupId) throws Exception {
        return await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(backupId, engine.saves, "Restored"), ProgressListener.NO_OP))
                .restoredWorldDirectory();
    }

    /** Blocks the first world copy until the test releases it. */
    private SourceCaptureObserver holdFirstCapture() {
        AtomicBoolean first = new AtomicBoolean(true);
        return new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws InterruptedException {
                if (first.compareAndSet(true, false)) {
                    copying.countDown();
                    release.await();
                }
            }
        };
    }

    private static void assertNoWorldCopies(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            List<Path> copies = files.filter(file -> file.getFileName().toString().equals("level.dat")).toList();
            assertFalse(copies.stream().findAny().isPresent(), copies.toString());
        }
    }

    /**
     * An integrated server that runs its tasks when the test says so, on the test's thread. Each
     * save rewrites level.dat, as the game's saves do.
     */
    private static final class FakeServer implements LiveServer {
        private final TestWorld world;

        private final Thread serverThread = Thread.currentThread();

        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        private final List<Boolean> autoSaveChanges = new ArrayList<>();

        private volatile boolean autoSave = true;

        private volatile int ticks;

        /** Runs once, from the next task that a thread other than the server's hands to the server. */
        private volatile Runnable beforeNextWorkerTask;

        private int saves;

        private FakeServer(TestWorld world) {
            this.world = world;
        }

        private void runTasks() {
            for (Runnable task = tasks.poll(); task != null; task = tasks.poll()) {
                task.run();
            }
        }

        @Override
        public Path worldDirectory() {
            return world.path();
        }

        @Override
        public String levelName() {
            return world.name();
        }

        @Override
        public void execute(Runnable task) {
            Runnable hook = beforeNextWorkerTask;
            if (hook != null && Thread.currentThread() != serverThread) {
                beforeNextWorkerTask = null;
                hook.run();
            }
            tasks.add(task);
        }

        @Override
        public boolean isServerThread() {
            return Thread.currentThread() == serverThread;
        }

        @Override
        public void saveAll() {
            saves++;
            try {
                world.write("level.dat", bytes(4_096, 100 + saves));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public boolean isAutoSave() {
            return autoSave;
        }

        @Override
        public void setAutoSave(boolean enabled) {
            autoSave = enabled;
            autoSaveChanges.add(enabled);
        }

        @Override
        public int tickCount() {
            return ticks;
        }
    }

    /** What the player would see: the outcome of the world-exit backup, a missing one, and scheduled warnings. */
    private static final class Reports implements BackgroundReports {
        private final List<Runnable> started = new CopyOnWriteArrayList<>();

        private final CompletableFuture<Notice> exitOutcome = new CompletableFuture<>();

        private final CompletableFuture<Notice> notMade = new CompletableFuture<>();

        private final List<Notice> warnings = new CopyOnWriteArrayList<>();

        @Override
        public ExitReport exitStarted(Runnable cancel) {
            started.add(cancel);
            return new ExitReport() {
                @Override
                public void onProgress(OperationProgress progress) {
                }

                @Override
                public void finished(Notice notice) {
                    exitOutcome.complete(notice);
                }
            };
        }

        @Override
        public void exitNotMade(Notice notice) {
            notMade.complete(notice);
        }

        @Override
        public void scheduledWarning(Notice notice) {
            warnings.add(notice);
        }
    }
}
