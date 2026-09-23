package dev.ishaanko.worldarchive.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class AsyncTasksTest {
    @Test
    void stopBeforeStartNeverRunsTheOperation() {
        AtomicBoolean ran = new AtomicBoolean();
        List<Runnable> queued = new ArrayList<>();
        AsyncTasks.InterruptibleFuture<String> future = AsyncTasks.supplyInterruptible(
                queued::add,
                () -> {
                    ran.set(true);
                    return "ran";
                });

        future.stop(true);
        queued.forEach(Runnable::run);

        assertThrows(CancellationException.class, future::join);
        assertFalse(ran.get());
    }

    @Test
    void stopInterruptsTheWorkerAndKeepsItsOutcome() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            AsyncTasks.InterruptibleFuture<String> future = AsyncTasks.supplyInterruptible(
                    executor,
                    () -> {
                        started.countDown();
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                            return "finished";
                        } catch (InterruptedException exception) {
                            return "kept after interrupt";
                        }
                    });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            future.stop(true);

            assertEquals("kept after interrupt", future.get(5, TimeUnit.SECONDS));
            assertFalse(future.isCancelled());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void dependentsRunWithTheInterruptCleared() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            AsyncTasks.InterruptibleFuture<String> future = AsyncTasks.supplyInterruptible(
                    executor,
                    () -> {
                        started.countDown();
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                            return "finished";
                        } catch (InterruptedException exception) {
                            // A backend that keeps its outcome restores the flag, as a Git push does.
                            Thread.currentThread().interrupt();
                            return "kept after interrupt";
                        }
                    });
            CompletableFuture<Boolean> dependentSawInterrupt = future.thenApply(
                    ignored -> Thread.currentThread().isInterrupted());
            assertTrue(started.await(5, TimeUnit.SECONDS));

            future.stop(true);

            assertFalse(dependentSawInterrupt.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
