package dev.ishaanko.worldarchive.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Starts asynchronous work without leaking executor rejection to the caller. */
public final class AsyncTasks {
    private AsyncTasks() {
    }

    public static <T> CompletionStage<T> supply(
            Executor executor,
            Supplier<T> operation) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(operation, "operation");
        try {
            return CompletableFuture.supplyAsync(operation, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /**
     * Like {@link #supply}, but cancelling the returned stage interrupts the thread that runs
     * the operation, so a long write can stop early. The interrupt flag is cleared before the
     * thread returns to its pool.
     */
    public static <T> CompletionStage<T> supplyInterruptible(
            Executor executor,
            Supplier<T> operation) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> result = new CompletableFuture<>();
        InterruptibleWorker worker = new InterruptibleWorker();
        result.whenComplete((ignored, throwable) -> {
            if (result.isCancelled()) {
                worker.interrupt();
            }
        });
        try {
            executor.execute(() -> {
                if (!worker.start()) {
                    return;
                }
                try {
                    result.complete(operation.get());
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                } finally {
                    worker.finish();
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    public static CompletionStage<Void> run(
            Executor executor,
            Runnable operation) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(operation, "operation");
        try {
            return CompletableFuture.runAsync(operation, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Tracks the thread that runs one operation so a cancellation can interrupt it. */
    private static final class InterruptibleWorker {
        private Thread thread;

        private boolean interruptRequested;

        /** Claims the calling thread; false when the operation was cancelled before it started. */
        synchronized boolean start() {
            if (interruptRequested) {
                return false;
            }
            thread = Thread.currentThread();
            return true;
        }

        synchronized void interrupt() {
            interruptRequested = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        /** Releases the thread; an interrupt that landed during the operation is cleared here. */
        void finish() {
            synchronized (this) {
                thread = null;
            }
            Thread.interrupted();
        }
    }
}
