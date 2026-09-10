package dev.ishaanko.worldarchive.core;

import java.util.Objects;
import java.util.concurrent.CancellationException;
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
     * Runs an operation whose worker can be stopped through the returned future. Unlike a
     * plain cancel, {@link InterruptibleFuture#stop} keeps the operation's own outcome, so a
     * backend that had already published a durable artifact still reports it.
     */
    public static <T> InterruptibleFuture<T> supplyInterruptible(
            Executor executor,
            InterruptibleOperation<T> operation) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(operation, "operation");
        InterruptibleFuture<T> result = new InterruptibleFuture<>();
        try {
            executor.execute(() -> result.run(operation));
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

    /** One blocking operation that may be interrupted while it waits on locks or I/O. */
    @FunctionalInterface
    public interface InterruptibleOperation<T> {
        T run() throws Exception;
    }

    /** Future whose worker thread can be interrupted without discarding the outcome. */
    public static final class InterruptibleFuture<T> extends CompletableFuture<T> {
        private final Object lock = new Object();

        // Guarded by lock.
        private Thread worker;

        private boolean stopRequested;

        private InterruptibleFuture() {
        }

        /**
         * Stops the operation. One that has not started never runs, and the future completes
         * with a {@link CancellationException}. A running one is interrupted when
         * {@code mayInterruptIfRunning} is set, and still completes with its own outcome.
         */
        public void stop(boolean mayInterruptIfRunning) {
            synchronized (lock) {
                stopRequested = true;
                if (worker != null && mayInterruptIfRunning) {
                    worker.interrupt();
                }
            }
        }

        /** Cancelling stops the worker too; the outcome is then the cancellation itself. */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                stop(mayInterruptIfRunning);
            }
            return cancelled;
        }

        private void run(InterruptibleOperation<T> operation) {
            synchronized (lock) {
                if (stopRequested || isDone()) {
                    completeExceptionally(
                            new CancellationException("Operation was stopped before it started"));
                    return;
                }
                worker = Thread.currentThread();
            }
            try {
                complete(operation.run());
            } catch (Throwable throwable) {
                completeExceptionally(throwable);
            } finally {
                synchronized (lock) {
                    worker = null;
                }
                // An interrupt that landed during the operation must not follow the thread
                // back to its pool.
                Thread.interrupted();
            }
        }
    }
}
