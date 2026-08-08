package dev.ishaankot.worldarchive.storage.git;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Cancellation-aware executor ownership for Git storage operations. */
final class GitAsyncExecutor implements AutoCloseable {
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    GitAsyncExecutor(ExecutorService executor, boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    <T> CompletableFuture<T> submit(ThrowingOperation<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        Future<?> task = executor.submit(() -> run(operation, result));
        taskReference.set(task);
        result.whenComplete((ignored, throwable) -> cancelSubmitted(result, taskReference));
        if (result.isCancelled()) {
            task.cancel(true);
        }
        return result;
    }

    private static <T> void run(
            ThrowingOperation<T> operation,
            CompletableFuture<T> result) {
        if (result.isCancelled()) {
            return;
        }
        try {
            result.complete(operation.run());
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
        }
    }

    private static void cancelSubmitted(
            CompletableFuture<?> result,
            AtomicReference<Future<?>> taskReference) {
        if (result.isCancelled()) {
            Future<?> submitted = taskReference.get();
            if (submitted != null) {
                submitted.cancel(true);
            }
        }
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

    @FunctionalInterface
    interface ThrowingOperation<T> {
        T run() throws Exception;
    }
}
