package dev.ishaanko.worldarchive.recovery;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * The future of one recovery operation, which also tells the operation where it may stop. The
 * operation runs on one worker thread and receives this task.
 *
 * <p>Cancel records the request and interrupts the worker, unless the worker is inside a commit:
 * a storage change that must finish so the catalog matches the files. The future completes only
 * once the worker has stopped, with a {@link CancellationException} when the request stopped the
 * work, or with the operation's result when the work was past its last stopping point. So, unlike
 * a plain {@link CompletableFuture}, {@link #isDone()} can still be false right after a successful
 * {@link #cancel}; that is what keeps dependents from running while the operation still writes.
 * A task cancelled before it starts never runs. After the point of no return, such as the rename
 * that publishes a restored world, cancel has no effect.</p>
 */
final class CancellableTask<T> extends CompletableFuture<T> implements Runnable {
    private final Operation<T> operation;

    // Guarded by this.
    private State state = State.WAITING;

    private Thread worker;

    private boolean cancelRequested;

    private boolean interruptWanted;

    private int commitDepth;

    CancellableTask(Operation<T> operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /** One recovery operation; it calls the task's checkpoints and commits as it goes. */
    @FunctionalInterface
    interface Operation<T> {
        T run(CancellableTask<T> task) throws Exception;
    }

    /** Storage work inside a commit. */
    @FunctionalInterface
    interface Commit<R> {
        R run() throws Exception;
    }

    private enum State {
        WAITING,
        CANCELLED_BEFORE_START,
        RUNNING,
        PAST_POINT_OF_NO_RETURN,
        FINISHED
    }

    @Override
    public void run() {
        synchronized (this) {
            if (state != State.WAITING) {
                return;
            }
            state = State.RUNNING;
            worker = Thread.currentThread();
        }
        T value = null;
        Throwable failure = null;
        try {
            checkpoint();
            value = operation.run(this);
        } catch (Throwable throwable) {
            failure = throwable;
        }
        boolean cancelled;
        synchronized (this) {
            state = State.FINISHED;
            worker = null;
            cancelled = cancelRequested;
            if (cancelled && interruptWanted) {
                // The interrupt was this task's own; the next task on this thread must not see it.
                Thread.interrupted();
            }
        }
        if (failure == null) {
            complete(value);
        } else if (cancelled) {
            CancellationException cancellation = new CancellationException("The operation was cancelled");
            cancellation.initCause(failure);
            completeExceptionally(cancellation);
        } else {
            completeExceptionally(failure);
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (this) {
            if (state == State.RUNNING) {
                cancelRequested = true;
                interruptWanted |= mayInterruptIfRunning;
                if (mayInterruptIfRunning && commitDepth == 0) {
                    worker.interrupt();
                }
                return true;
            }
            if (state != State.WAITING) {
                return false;
            }
            state = State.CANCELLED_BEFORE_START;
        }
        return super.cancel(false);
    }

    /** Stops here when cancel was requested or the worker was interrupted. */
    void checkpoint() throws InterruptedException {
        boolean requested;
        synchronized (this) {
            requested = cancelRequested;
        }
        if (requested || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("The operation was cancelled");
        }
    }

    /** Runs a commit unless cancel was already requested; a later request waits until it ends. */
    <R> R commitIfActive(Commit<R> commit) throws Exception {
        synchronized (this) {
            if (cancelRequested || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("The operation was cancelled");
            }
            commitDepth++;
        }
        return runCommit(commit);
    }

    /** Runs a commit even after a cancel request; the interrupt waits until it ends. */
    <R> R mandatoryCommit(Commit<R> commit) throws Exception {
        synchronized (this) {
            commitDepth++;
        }
        return runCommit(commit);
    }

    /** Runs the publication that ends the operation; from here on, cancel has no effect. */
    <R> R pointOfNoReturn(Commit<R> publication) throws Exception {
        synchronized (this) {
            if (cancelRequested || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("The operation was cancelled");
            }
            state = State.PAST_POINT_OF_NO_RETURN;
        }
        return publication.run();
    }

    private <R> R runCommit(Commit<R> commit) throws Exception {
        boolean interruptedBefore = Thread.interrupted();
        try {
            return commit.run();
        } finally {
            boolean deliver;
            synchronized (this) {
                commitDepth--;
                deliver = interruptedBefore || commitDepth == 0 && cancelRequested && interruptWanted;
            }
            if (deliver) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
