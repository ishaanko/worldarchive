package dev.ishaankot.worldarchive.recovery;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Future-backed maintenance task that preserves mandatory commits during cancellation. */
final class CancellableTask<T> extends CompletableFuture<T>
        implements Runnable, OperationCancellation {
    private final Operation<T> operation;

    private Thread runner;

    private boolean cancellationRequested;

    private boolean interruptRequested;

    private int mandatoryCommitDepth;

    private boolean pointOfNoReturn;

    CancellableTask(Operation<T> operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public void run() {
        synchronized (this) {
            if (isDone()) {
                return;
            }
            runner = Thread.currentThread();
        }
        try {
            checkpoint();
            complete(operation.get(this));
        } catch (Throwable exception) {
            if (!isCancelled()) {
                completeExceptionally(exception);
            }
        } finally {
            synchronized (this) {
                runner = null;
            }
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (this) {
            if (pointOfNoReturn || isDone() || !super.cancel(false)) {
                return false;
            }
            cancellationRequested = true;
            interruptRequested = mayInterruptIfRunning;
            if (mayInterruptIfRunning && mandatoryCommitDepth == 0 && runner != null) {
                runner.interrupt();
            }
        }
        return true;
    }

    @Override
    public void checkpoint() throws InterruptedException {
        boolean cancelled;
        synchronized (this) {
            cancelled = cancellationRequested;
        }
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Backup maintenance operation was cancelled");
        }
    }

    @Override
    public <R> R mandatoryCommit(CheckedSupplier<R> commit) throws Exception {
        return commit(commit, false);
    }

    @Override
    public <R> R commitIfActive(CheckedSupplier<R> commit) throws Exception {
        return commit(commit, true);
    }

    @Override
    public <R> R pointOfNoReturn(CheckedSupplier<R> publication) throws Exception {
        Objects.requireNonNull(publication, "publication");
        synchronized (this) {
            if (cancellationRequested || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Backup maintenance operation was cancelled");
            }
            pointOfNoReturn = true;
        }
        return publication.get();
    }

    private <R> R commit(CheckedSupplier<R> commit, boolean requireActive) throws Exception {
        Objects.requireNonNull(commit, "commit");
        synchronized (this) {
            if (requireActive
                    && (cancellationRequested || Thread.currentThread().isInterrupted())) {
                throw new InterruptedException("Backup maintenance operation was cancelled");
            }
            mandatoryCommitDepth++;
        }
        boolean interruptedBeforeCommit = Thread.interrupted();
        try {
            return commit.get();
        } finally {
            boolean restoreInterrupt;
            synchronized (this) {
                mandatoryCommitDepth--;
                restoreInterrupt = mandatoryCommitDepth == 0 && interruptRequested;
            }
            if (interruptedBeforeCommit
                    || restoreInterrupt
                    || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    interface Operation<T> {
        T get(OperationCancellation cancellation) throws Exception;
    }
}
