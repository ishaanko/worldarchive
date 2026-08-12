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
