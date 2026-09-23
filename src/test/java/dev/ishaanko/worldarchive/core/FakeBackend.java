package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A destination for coordinator tests. Each write runs on its destination worker, like a real backend. */
final class FakeBackend implements BackupBackend {
    final AtomicInteger calls = new AtomicInteger();

    private final DestinationType destination;

    private final Write write;

    private FakeBackend(DestinationType destination, Write write) {
        this.destination = destination;
        this.write = write;
    }

    static FakeBackend success(DestinationType destination) {
        return writing(destination, (capture, listener) ->
                DestinationResult.success(destination, destination.name().toLowerCase()));
    }

    static FakeBackend writing(DestinationType destination, Write write) {
        return new FakeBackend(destination, write);
    }

    /** Each write waits for the future that {@code release} returns; a failed future means no trustworthy result. */
    static FakeBackend awaiting(DestinationType destination, Function<BackupCapture, Future<DestinationResult>> release) {
        return writing(destination, (capture, listener) -> {
            try {
                return release.apply(capture).get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Fake destination failed without a result", exception.getCause());
            }
        });
    }

    @Override
    public DestinationType destinationType() {
        return destination;
    }

    @Override
    public DestinationResult createBackup(BackupCapture capture, ProgressListener progressListener)
            throws InterruptedException {
        calls.incrementAndGet();
        return write.run(capture, progressListener);
    }

    /** A blocking write on the destination worker. */
    @FunctionalInterface
    interface Write {
        DestinationResult run(BackupCapture capture, ProgressListener listener) throws InterruptedException;
    }
}
