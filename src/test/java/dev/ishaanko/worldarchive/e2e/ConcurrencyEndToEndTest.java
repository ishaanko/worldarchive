package dev.ishaanko.worldarchive.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.core.CaptureKind;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.core.PreparedBackup;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.ProgressListener;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrencyEndToEndTest {
    @TempDir
    Path root;

    private final CountDownLatch capturing = new CountDownLatch(1);

    private final CountDownLatch release = new CountDownLatch(1);

    private final AtomicInteger levelCopies = new AtomicInteger();

    private Engine engine;

    @AfterEach
    void closeEngine() throws Exception {
        release.countDown();
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void oneWorldsBackupDoesNotWaitForAnotherWorld() throws Exception {
        engine = start(holdFirstCapture());
        TestWorld slow = TestWorld.create(engine, "Slow");
        TestWorld fast = TestWorld.create(engine, "Fast");

        CompletableFuture<BackupResult> held = backup(slow);
        assertTrue(capturing.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        BackupResult finished = backup(fast).get(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertEquals(BackupStatus.SUCCESS, finished.status());
        assertFalse(held.isDone());
        release.countDown();
        assertEquals(BackupStatus.SUCCESS, held.get(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS).status());
    }

    /** The player quits while a scheduled capture still copies: the exit capture waits for it instead of failing. */
    @Test
    void aSecondCaptureOfTheSameWorldWaitsForTheFirstAndBothSucceed() throws Exception {
        engine = start(holdFirstCapture());
        TestWorld world = TestWorld.create(engine, "Quitting");
        CountDownLatch exitStarted = new CountDownLatch(1);

        CompletableFuture<PreparedBackup> scheduled = prepare(world, BackupTrigger.SCHEDULED, ProgressListener.NO_OP);
        assertTrue(capturing.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        CompletableFuture<PreparedBackup> exit = prepare(world, BackupTrigger.WORLD_EXIT, progress -> exitStarted.countDown());
        assertTrue(exitStarted.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));

        assertFalse(exit.isDone());
        release.countDown();
        BackupResult first = Engine.await(engine.coordinator.createPreparedBackup(
                scheduled.get(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS), ProgressListener.NO_OP));
        BackupResult second = Engine.await(engine.coordinator.createPreparedBackup(
                exit.get(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS), ProgressListener.NO_OP));
        assertEquals(BackupStatus.SUCCESS, first.status());
        assertEquals(BackupStatus.SUCCESS, second.status());
        assertEquals(2, engine.records(world.id()).size());
    }

    @Test
    void cancellingABackupThatWaitsForTheWorldNeverCapturesItAndTheNextBackupRuns() throws Exception {
        engine = start(holdFirstCapture());
        TestWorld world = TestWorld.create(engine, "Queued");
        CompletableFuture<BackupResult> first = backup(world);
        assertTrue(capturing.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        CompletableFuture<BackupResult> waiting = backup(world);

        assertTrue(waiting.cancel(true));
        release.countDown();

        assertEquals(BackupStatus.SUCCESS, first.get(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS).status());
        assertTrue(waiting.isCancelled());
        engine.clock.advance(Duration.ofMinutes(1));
        assertEquals(BackupStatus.SUCCESS, engine.backupNow(world, BackupTrigger.MANUAL).status());
        assertEquals(2, levelCopies.get());
        assertEquals(2, engine.records(world.id()).size());
    }

    /** Holds the first copy of level.dat until the test releases it, and counts every copy of it. */
    private SourceCaptureObserver holdFirstCapture() {
        return new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws InterruptedException {
                if (relativePath.toString().equals("level.dat") && levelCopies.incrementAndGet() == 1) {
                    capturing.countDown();
                    release.await();
                }
            }
        };
    }

    private CompletableFuture<BackupResult> backup(TestWorld world) {
        return engine.backup(world, BackupTrigger.MANUAL, Optional.empty()).toCompletableFuture();
    }

    /** Captures on a thread of its own, as the game does: the open world for a schedule, the closed one at exit. */
    private CompletableFuture<PreparedBackup> prepare(TestWorld world, BackupTrigger trigger, ProgressListener listener) {
        CreateBackupRequest request = new CreateBackupRequest(world.id(), world.path(), world.name(), Optional.empty(), trigger);
        return CompletableFuture.supplyAsync(() -> {
            try {
                CaptureKind kind = trigger == BackupTrigger.WORLD_EXIT
                        ? CaptureKind.CLOSED_WORLD
                        : CaptureKind.OPEN_WORLD;
                return engine.coordinator.prepareCapture(request, kind, listener);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, runnable -> Thread.ofVirtual().start(runnable));
    }

    private Engine start(SourceCaptureObserver observer) {
        return new Engine(
                root,
                new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")),
                Engine.defaultConfig(),
                observer);
    }
}
