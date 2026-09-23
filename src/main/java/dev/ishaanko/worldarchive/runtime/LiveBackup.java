package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * One backup of the open or the just-closed world, from its request to its result. A cancel stops
 * it wherever it is: before the copy, during the copy, or while its destinations write, unless the
 * coordinator already records the result. {@link LiveWorldBackups} drives it.
 */
final class LiveBackup {
    /** No tick was recorded: the backup did not save a running world. */
    static final int NO_TICK = -1;

    final LiveServer server;

    final RuntimeState state;

    final CreateBackupRequest request;

    final CompletableFuture<BackupResult> result = new CompletableFuture<>();

    private final Owner owner;

    /** The server's tick count when it saved the world for this backup; set on the server thread before the copy. */
    private volatile int savedAtTick = NO_TICK;

    // Guarded by this. A cancel interrupts the copy, or cancels the destinations once they write.
    private boolean cancelRequested;

    private boolean captureStarted;

    private Thread captureThread;

    private CompletableFuture<BackupResult> operation;

    /** @param permit a work permit, released once the backup settled and its world's backup work stopped */
    LiveBackup(
            LiveServer server,
            RuntimeState state,
            CreateBackupRequest request,
            ConfigurationGate.Permit permit,
            Owner owner) {
        this.server = Objects.requireNonNull(server, "server");
        this.state = Objects.requireNonNull(state, "state");
        this.request = Objects.requireNonNull(request, "request");
        this.owner = Objects.requireNonNull(owner, "owner");
        // A cancelled backup settles before its destinations stopped; the folders stay put until then.
        result.whenComplete((ignored, failure) -> state.coordinator().whenIdle(request.worldId())
                .whenComplete((idle, error) -> permit.close()));
    }

    /** What the backup tells {@link LiveWorldBackups}, which owns the save queue and the schedule. */
    interface Owner {
        /** Forgets the backup's save that still waits for the server thread. */
        void dropSave(LiveBackup backup);

        /** The backup kept a copy of the world as the server saved it at {@code tick}. */
        void keptAt(LiveBackup backup, int tick);
    }

    WorldId worldId() {
        return request.worldId();
    }

    void savedAt(int tick) {
        savedAtTick = tick;
    }

    /**
     * Asks the backup to stop. A backup whose copy has not started never copies; a copy that runs
     * is interrupted; destinations that write are cancelled unless the result is being recorded.
     * True when the backup ends as cancelled.
     */
    boolean cancel() {
        CompletableFuture<BackupResult> running;
        synchronized (this) {
            if (result.isDone()) {
                return false;
            }
            cancelRequested = true;
            if (captureThread != null) {
                captureThread.interrupt();
                return true;
            }
            if (captureStarted && operation == null) {
                // Between the copy and the destinations: dispatched() sees the request.
                return true;
            }
            running = operation;
        }
        if (running == null) {
            owner.dropSave(this);
            fail(cancellation());
            return true;
        }
        return running.cancel(true);
    }

    /** Claims the calling thread for the copy; false when a cancel came first. */
    synchronized boolean beginCapture() {
        captureStarted = true;
        if (cancelRequested) {
            return false;
        }
        captureThread = Thread.currentThread();
        return true;
    }

    /** Ends the copy and clears an interrupt a cancel left; false when the backup must stop here. */
    boolean endCapture() {
        boolean proceed;
        synchronized (this) {
            captureThread = null;
            proceed = !cancelRequested;
        }
        if (!proceed) {
            Thread.interrupted();
        }
        return proceed;
    }

    /** Records the destinations' work, and cancels it when a cancel arrived meanwhile. */
    void dispatched(CompletableFuture<BackupResult> running) {
        boolean cancel;
        synchronized (this) {
            operation = running;
            cancel = cancelRequested;
        }
        if (cancel) {
            running.cancel(true);
        }
    }

    /** Completes the backup with its outcome; a kept copy of a saved world marks the tick the schedule compares with. */
    void settle(BackupResult value, Throwable failure) {
        if (failure != null) {
            fail(failure);
            return;
        }
        if (savedAtTick != NO_TICK && value.destinations().stream().anyMatch(DestinationResult::isDurable)) {
            owner.keptAt(this, savedAtTick);
        }
        result.complete(value);
    }

    void fail(Throwable failure) {
        result.completeExceptionally(failure);
    }

    /** The stage handed to the player's screen: it follows the result, and cancelling it cancels the backup. */
    CompletableFuture<BackupResult> view() {
        CompletableFuture<BackupResult> view = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return LiveBackup.this.cancel() && (super.cancel(mayInterruptIfRunning) || isCancelled());
            }
        };
        result.whenComplete((value, failure) -> {
            if (failure == null) {
                view.complete(value);
            } else {
                view.completeExceptionally(failure);
            }
        });
        return view;
    }

    static CancellationException cancellation() {
        return new CancellationException("Backup was cancelled");
    }
}
