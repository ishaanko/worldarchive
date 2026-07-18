package dev.ishaankot.worldarchive.core;

import java.io.IOException;

/** Marshals the short immutable-copy phase onto a caller-controlled capture thread. */
@FunctionalInterface
public interface BackupCaptureGate {
    BackupCaptureGate DIRECT = CaptureTask::capture;

    /**
     * Runs the task and returns only after the gated thread has fully left the capture section.
     * Destination work is not started until this method returns.
     */
    CapturedBackup capture(CaptureTask task) throws IOException, InterruptedException;

    @FunctionalInterface
    interface CaptureTask {
        CapturedBackup capture() throws IOException, InterruptedException;
    }
}
