package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.core.AsyncTasks;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cancellation-aware executor ownership for Git storage operations. A submitted operation
 * can be stopped through its future without losing the outcome it produces, which is how a
 * snapshot published before an interrupted push still reaches the catalog.
 */
final class GitAsyncExecutor implements AutoCloseable {
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    GitAsyncExecutor(ExecutorService executor, boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    <T> CompletableFuture<T> submit(AsyncTasks.InterruptibleOperation<T> operation) {
        return AsyncTasks.supplyInterruptible(executor, operation);
    }

    @Override
    public void close() {
        if (!ownsExecutor) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
