package dev.ishaanko.worldarchive.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What quitting the game does to a backup that is still running. */
class ShutdownEndToEndTest {
    /** How long the held copy takes to stop once interrupted, like a Git process that is being stopped. */
    private static final long STOP_MILLIS = 300;

    @TempDir
    Path root;

    private final CountDownLatch copying = new CountDownLatch(1);

    private Engine engine;

    @AfterEach
    void closeEngine() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void quitStopsARunningBackupAndWaitsForItsCleanup() throws Exception {
        engine = new Engine(
                root,
                new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")),
                Engine.onlyDestination(DestinationType.ZIP),
                holdCopyUntilInterrupted());
        TestWorld world = TestWorld.create(engine, "Quit World");
        CompletableFuture<BackupResult> backup = engine.backup(world, BackupTrigger.WORLD_EXIT, Optional.empty())
                .toCompletableFuture();
        assertTrue(copying.await(Engine.TIMEOUT.toSeconds(), TimeUnit.SECONDS));

        assertTrue(engine.graph.shutdown(false, Duration.ofSeconds(10)));

        assertTrue(backup.isCompletedExceptionally());
        assertEquals(List.of(), leftovers(engine.storage));
        assertTrue(engine.records(world.id()).isEmpty());
    }

    @Test
    void quitAfterTheWorkFinishedDoesNotInterruptIt() throws Exception {
        engine = new Engine(
                root,
                new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")),
                Engine.onlyDestination(DestinationType.ZIP),
                SourceCaptureObserver.NONE);
        TestWorld world = TestWorld.create(engine, "Quit World");
        engine.backupNow(world, BackupTrigger.WORLD_EXIT);

        assertTrue(engine.graph.shutdown(true, Duration.ofSeconds(10)));

        assertEquals(1, engine.records(world.id()).size());
        assertFalse(engine.graph.current().isPresent());
    }

    /** Holds the copy of level.dat until the quit interrupts it, then takes a while to stop. */
    private SourceCaptureObserver holdCopyUntilInterrupted() {
        return new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws IOException {
                if (!relativePath.toString().equals("level.dat")) {
                    return;
                }
                copying.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_MILLIS);
                    while (System.nanoTime() < end) {
                        Thread.onSpinWait();
                    }
                    throw new InterruptedIOException("The copy was stopped");
                }
            }
        };
    }

    /** Files a stopped backup could leave: world copies and partial archives, but not the capture owner's lock. */
    private static List<Path> leftovers(Path storage) throws IOException {
        try (Stream<Path> files = Files.walk(storage)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.startsWith(storage.resolve("capture-temp"))
                            ? !file.getFileName().toString().endsWith(".lock")
                            : file.getFileName().toString().endsWith(".partial"))
                    .toList();
        }
    }
}
