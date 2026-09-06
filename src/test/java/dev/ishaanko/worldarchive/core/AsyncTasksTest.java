package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
    void cancellingAnInterruptibleSupplyInterruptsTheWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<String> result = AsyncTasks.supplyInterruptibly(executor, () -> {
                entered.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                }
                return "done";
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertTrue(result.cancel(true));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void supplyInterruptiblyExecutorRejectionCompletesTheReturnedStage() {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> AsyncTasks.supplyInterruptibly(
                                command -> {
                                    throw new RejectedExecutionException("rejected");
                                },
                                () -> "unused")
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
}
