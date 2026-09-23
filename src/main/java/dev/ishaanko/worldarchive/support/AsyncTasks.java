package dev.ishaanko.worldarchive.support;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Starts asynchronous work and waits for it. An executor that rejects the work completes the
 * returned stage with the rejection instead of throwing at the caller.
 */
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
     * Like {@link #supply} for work that throws checked exceptions. A checked exception
     * completes the stage wrapped in a {@link CompletionException}, the same shape that
     * {@link CompletableFuture#supplyAsync} gives an unchecked one.
     */
    public static <T> CompletionStage<T> supplyChecked(
            Executor executor,
            CheckedSupplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return supply(executor, () -> {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
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

    /**
     * Waits for a stage and rethrows the cause of its failure as it was thrown. An interrupt
     * while waiting restores the thread's interrupt flag and throws
     * {@link InterruptedException}; a cancelled stage throws {@link CancellationException}.
     */
    public static <T> T await(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CompletionException(cause);
        }
    }

    /** True when a failure is a cancellation, directly or wrapped by a completion stage. */
    public static boolean isCancellation(Throwable failure) {
        return failure instanceof CancellationException
                || failure != null && failure.getCause() instanceof CancellationException;
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

    /** Work for {@link #supplyChecked}. */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
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

        /**
         * Runs the operation, then detaches the worker and clears its interrupt before the
         * future completes. Dependents that run on this thread, such as a catalog write, must
         * never see an interrupt that was meant for the operation, because file locks and
         * channel I/O fail on an interrupted thread.
         */
        private void run(InterruptibleOperation<T> operation) {
            synchronized (lock) {
                if (stopRequested || isDone()) {
                    completeExceptionally(
                            new CancellationException("Operation was stopped before it started"));
                    return;
                }
                worker = Thread.currentThread();
            }
            T value = null;
            Throwable failure = null;
            try {
                value = operation.run();
            } catch (Throwable throwable) {
                failure = throwable;
            }
            synchronized (lock) {
                worker = null;
                // No stop() can interrupt this thread once the worker is detached.
                Thread.interrupted();
            }
            if (failure == null) {
                complete(value);
            } else {
                completeExceptionally(failure);
            }
        }
    }
}
