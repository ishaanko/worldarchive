package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Scriptable test destination that records create and discard calls. */
final class FakeBackend implements BackupBackend {
    private final DestinationType destination;

    private final BiFunction<BackupCapture, ProgressListener, CompletionStage<DestinationResult>> result;

    final AtomicInteger calls = new AtomicInteger();

    final List<BackupId> discards = new CopyOnWriteArrayList<>();

    volatile boolean discardFails;

    FakeBackend(
            DestinationType destination,
            Function<BackupCapture, CompletionStage<DestinationResult>> result) {
        this(destination, (capture, ignored) -> result.apply(capture));
    }

    FakeBackend(
            DestinationType destination,
            BiFunction<BackupCapture, ProgressListener, CompletionStage<DestinationResult>> result) {
        this.destination = destination;
        this.result = result;
    }

    static FakeBackend success(DestinationType destination) {
        return new FakeBackend(destination, ignored -> CompletableFuture.completedFuture(
                DestinationResult.success(destination, destination.name().toLowerCase())));
    }

    @Override
    public DestinationType destinationType() {
        return destination;
    }

    @Override
    public CompletionStage<DestinationResult> createBackup(
            BackupCapture capture,
            ProgressListener progressListener) {
        calls.incrementAndGet();
        return result.apply(capture, progressListener);
    }

    @Override
    public CompletionStage<Boolean> discardBackup(BackupManifest manifest) {
        discards.add(manifest.backupId());
        return discardFails
                ? CompletableFuture.failedFuture(new IOException("simulated discard failure"))
                : CompletableFuture.completedFuture(true);
    }
}
