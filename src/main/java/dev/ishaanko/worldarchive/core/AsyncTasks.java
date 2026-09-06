package dev.ishaanko.worldarchive.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
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
     * Starts work whose returned future propagates {@code cancel(true)} as a thread interrupt,
     * so a cancelled destination write can stop and clean up instead of running to completion.
     */
    public static <T> CompletableFuture<T> supplyInterruptibly(
            Executor executor,
            Supplier<T> operation) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (result.isCancelled()) {
                return null;
            }
            try {
                result.complete(operation.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
            return null;
        });
        result.whenComplete((ignored, throwable) -> {
            if (result.isCancelled()) {
                task.cancel(true);
            }
        });
        try {
            executor.execute(task);
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
}
