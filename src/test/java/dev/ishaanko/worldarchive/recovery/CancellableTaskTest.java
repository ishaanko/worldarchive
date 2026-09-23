package dev.ishaanko.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The cancellation contract of recovery operations: storage work that started always finishes first. */
final class CancellableTaskTest {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void aCancelDuringACommitWaitsForItAndStopsAtTheNextCheckpoint() throws Exception {
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean commitInterrupted = new AtomicBoolean();
        AtomicInteger stepsAfterCommit = new AtomicInteger();
        CancellableTask<String> task = new CancellableTask<>(self -> {
            self.commitIfActive(() -> {
                committing.countDown();
                release.await();
                commitInterrupted.set(Thread.currentThread().isInterrupted());
                return null;
            });
            self.checkpoint();
            stepsAfterCommit.incrementAndGet();
            return "finished";
        });
        executor.execute(task);
        assertTrue(committing.await(5, TimeUnit.SECONDS));

        assertTrue(task.cancel(true));
        assertFalse(task.isDone(), "the task completes only once the commit and the worker are done");
        release.countDown();

        assertThrows(CancellationException.class, () -> task.get(5, TimeUnit.SECONDS));
        assertFalse(commitInterrupted.get());
        assertEquals(0, stepsAfterCommit.get());
        assertTrue(task.isCancelled());
    }

    @Test
    void aTaskCancelledBeforeItStartsNeverRuns() {
        AtomicBoolean ran = new AtomicBoolean();
        CancellableTask<String> task = new CancellableTask<>(self -> {
            ran.set(true);
            return "ran";
        });

        assertTrue(task.cancel(true));
        task.run();

        assertTrue(task.isCancelled());
        assertFalse(ran.get());
    }

    @Test
    void afterThePointOfNoReturnCancelHasNoEffect() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CancellableTask<String> task = new CancellableTask<>(self -> self.pointOfNoReturn(() -> {
            publishing.countDown();
            release.await();
            return "published";
        }));
        executor.execute(task);
        assertTrue(publishing.await(5, TimeUnit.SECONDS));

        assertFalse(task.cancel(true));
        release.countDown();

        assertEquals("published", task.get(5, TimeUnit.SECONDS));
    }
}
