package dev.ishaanko.worldarchive.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps backup folders from moving while WorldArchive writes into them. Work that writes backups,
 * restores, deletes or imports holds a permit from {@link #enterWork}. A settings save that moves
 * a backup folder takes {@link #enterFolderChange}, which is refused while any permit is held; new
 * work is refused while that save runs. Neither waits: a refusal is an exception whose message
 * the player can act on. Only {@link #awaitIdle}, for shutdown, waits.
 */
public final class ConfigurationGate {
    private final Runnable idle;

    // Guarded by this.
    private int work;

    private boolean folderChange;

    /** @param idle runs outside the gate's lock each time the last work permit is released */
    public ConfigurationGate(Runnable idle) {
        this.idle = Objects.requireNonNull(idle, "idle");
    }

    /** A permit for work that writes backups; refused while a settings save moves backup folders. */
    public synchronized Permit enterWork() {
        if (folderChange) {
            throw new IllegalStateException("WorldArchive settings are being saved. Try again in a moment.");
        }
        work++;
        return new Permit(this::leaveWork);
    }

    /**
     * A permit for work that a settings change itself starts, such as the first catalog scan of
     * the new folders. It is granted even while that change runs.
     */
    synchronized Permit enterSettingsWork() {
        work++;
        return new Permit(this::leaveWork);
    }

    /** A permit for a settings save that moves backup folders; refused while any work runs. */
    public synchronized Permit enterFolderChange() {
        if (folderChange || work > 0) {
            throw new IllegalStateException("Backup folders cannot change while a backup, restore, delete or"
                    + " import is running. Try again when it has finished.");
        }
        folderChange = true;
        return new Permit(this::leaveFolderChange);
    }

    /** Waits until no work holds a permit, for at most {@code timeout}; true when none does. */
    public synchronized boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (work > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    private void leaveWork() {
        boolean nowIdle;
        synchronized (this) {
            work--;
            nowIdle = work == 0;
            notifyAll();
        }
        if (nowIdle) {
            idle.run();
        }
    }

    private synchronized void leaveFolderChange() {
        folderChange = false;
    }

    /** One hold on the gate; closing it releases the hold once, from any thread. */
    public static final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        private final Runnable release;

        private Permit(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
