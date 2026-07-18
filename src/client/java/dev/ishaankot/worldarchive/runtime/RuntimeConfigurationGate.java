package dev.ishaankot.worldarchive.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps backup publication and destination-path settings transactions from overlapping. */
final class RuntimeConfigurationGate {
    private int activeBackups;

    private boolean configurationChange;

    synchronized Permit enterBackup() {
        boolean interrupted = false;
        while (configurationChange) {
            try {
                wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        activeBackups++;
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Permit(this::leaveBackup);
    }

    synchronized Permit tryEnterConfigurationChange() {
        if (configurationChange || activeBackups != 0) {
            throw new IllegalStateException(
                    "Destination paths cannot change while a backup is in progress");
        }
        configurationChange = true;
        return new Permit(this::leaveConfigurationChange);
    }

    private synchronized void leaveBackup() {
        activeBackups--;
        if (activeBackups < 0) {
            throw new IllegalStateException("Backup configuration gate underflow");
        }
        notifyAll();
    }

    private synchronized void leaveConfigurationChange() {
        if (!configurationChange) {
            throw new IllegalStateException("No configuration change is active");
        }
        configurationChange = false;
        notifyAll();
    }

    static final class Permit implements AutoCloseable {
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
