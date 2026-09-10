package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class AsyncTasksTest {
    @Test
    void supplyExecutorRejectionCompletesTheReturnedStage() {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> AsyncTasks.supply(
                                command -> {
                                    throw new RejectedExecutionException("rejected");
                                },
                                () -> "unused")
                        .toCompletableFuture()
                        .join());

        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }

    @Test
    void runExecutorRejectionCompletesTheReturnedStage() {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> AsyncTasks.run(
                                command -> {
                                    throw new RejectedExecutionException("rejected");
                                },
                                () -> {
                                })
                        .toCompletableFuture()
                        .join());

        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }

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
}
