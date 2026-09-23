package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.CaptureProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * A second game instance for the capture workspace tests. It runs in a child JVM, captures a
 * world, prints the capture folder, and keeps the capture open until the test kills it.
 */
public final class CaptureHolder {
    private CaptureHolder() {
    }

    /** Arguments: the capture root and the world folder. */
    public static void main(String[] arguments) throws Exception {
        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(
                Path.of(arguments[0]), Optional.empty(), SourceCaptureObserver.NONE);
        CreateBackupRequest request = new CreateBackupRequest(
                WorldId.create(), Path.of(arguments[1]), "Held", Optional.empty(), BackupTrigger.MANUAL);
        CapturedBackup held = factory.capture(request, CaptureKind.CLOSED_WORLD, BackupId.create(), Instant.now(),
                Optional.empty(), CaptureProgressListener.NO_OP);
        // The instance's own housekeeping and a second capture run while the first one is open.
        factory.removeAbandonedCaptures();
        factory.capture(request, CaptureKind.CLOSED_WORLD, BackupId.create(), Instant.now(), Optional.empty(),
                CaptureProgressListener.NO_OP).close();
        System.out.println(held.capture().worldDirectory());
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
