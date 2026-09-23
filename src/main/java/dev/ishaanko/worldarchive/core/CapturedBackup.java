package dev.ishaanko.worldarchive.core;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A private capture shared by all destinations and deleted after their terminal results. */
public final class CapturedBackup implements AutoCloseable {
    private final BackupCapture capture;

    private final CloseAction closeAction;

    private final AtomicBoolean closed = new AtomicBoolean();

    CapturedBackup(BackupCapture capture, CloseAction closeAction) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    public BackupCapture capture() {
        if (closed.get()) {
            throw new IllegalStateException("Backup capture is already closed");
        }
        return capture;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            closeAction.close();
        }
    }

    @FunctionalInterface
    interface CloseAction {
        void close() throws IOException;
    }
}
