package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
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
}
