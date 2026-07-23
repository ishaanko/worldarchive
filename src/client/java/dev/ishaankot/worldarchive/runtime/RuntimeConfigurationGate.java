package dev.ishaankot.worldarchive.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps backup publication and destination-path settings transactions from overlapping. */
final class RuntimeConfigurationGate {
    private final Runnable idleCallback;

    private int activeBackups;

    private boolean configurationChange;

    private int waitingConfigurationChanges;

    RuntimeConfigurationGate() {
        this(() -> { });
    }

    RuntimeConfigurationGate(Runnable idleCallback) {
        this.idleCallback = Objects.requireNonNull(idleCallback, "idleCallback");
    }

    synchronized Permit enterBackup() {
        boolean interrupted = false;
        while (configurationChange || waitingConfigurationChanges != 0) {
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

    synchronized Permit retainStateWork() {
        activeBackups++;
        return new Permit(this::leaveBackup);
    }

    synchronized Permit tryEnterConfigurationChange() {
        if (configurationChange || waitingConfigurationChanges != 0 || activeBackups != 0) {
            throw new IllegalStateException(
                    "Destination paths cannot change while a backup is in progress");
        }
        configurationChange = true;
        return new Permit(this::leaveConfigurationChange);
    }

    synchronized Permit enterConfigurationChange() {
        waitingConfigurationChanges++;
        boolean interrupted = false;
        try {
            while (configurationChange || activeBackups != 0) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            configurationChange = true;
            return new Permit(this::leaveConfigurationChange);
        } finally {
            waitingConfigurationChanges--;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    synchronized Permit transitionBackupToConfigurationChange(Permit backupPermit) {
        Permit backup = Objects.requireNonNull(backupPermit, "backupPermit");
        waitingConfigurationChanges++;
        if (!backup.releaseOnce()) {
            waitingConfigurationChanges--;
            throw new IllegalStateException("Backup permit is already closed");
        }
        boolean interrupted = false;
        try {
            while (configurationChange || activeBackups != 0) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            configurationChange = true;
            return new Permit(this::leaveConfigurationChange);
        } finally {
            waitingConfigurationChanges--;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void leaveBackup() {
        boolean idle;
        synchronized (this) {
            activeBackups--;
            if (activeBackups < 0) {
                throw new IllegalStateException("Backup configuration gate underflow");
            }
            idle = activeBackups == 0;
            notifyAll();
        }
        if (idle) {
            idleCallback.run();
        }
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
            releaseOnce();
        }

        private boolean releaseOnce() {
            if (closed.compareAndSet(false, true)) {
                release.run();
                return true;
            }
            return false;
        }
    }
}
