package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Owned synchronous capture awaiting transfer to the destination queue. */
public final class PreparedBackup implements AutoCloseable {
    private final CreateBackupRequest request;

    private final BackupId backupId;

    private final OperationId operationId;

    private final boolean previousInventoryPresent;

    private final AtomicReference<Resources> ownership;

    private final AtomicReference<Runnable> releaseObserver;

    PreparedBackup(
            CreateBackupRequest request,
            CapturedBackup capturedBackup,
            boolean previousInventoryPresent,
            OperationId operationId,
            Runnable releaseObserver) {
        this.request = Objects.requireNonNull(request, "request");
        CapturedBackup captured = Objects.requireNonNull(capturedBackup, "capturedBackup");
        this.backupId = captured.capture().manifest().backupId();
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.previousInventoryPresent = previousInventoryPresent;
        this.ownership = new AtomicReference<>(new Resources(captured));
        this.releaseObserver = new AtomicReference<>(Objects.requireNonNull(
                releaseObserver,
                "releaseObserver"));
    }

    public CreateBackupRequest request() {
        return request;
    }

    public BackupId backupId() {
        return backupId;
    }

    boolean previousInventoryPresent() {
        return previousInventoryPresent;
    }

    OperationId operationId() {
        return operationId;
    }

    Resources claim() {
        Resources resources = ownership.getAndSet(null);
        if (resources == null) {
            throw new IllegalStateException("Prepared backup ownership was already transferred or closed");
        }
        notifyReleased();
        return resources;
    }

    @Override
    public void close() throws IOException {
        Resources resources = ownership.getAndSet(null);
        if (resources != null) {
            try {
                resources.close();
            } finally {
                notifyReleased();
            }
        }
    }

    private void notifyReleased() {
        Runnable observer = releaseObserver.getAndSet(null);
        if (observer != null) {
            observer.run();
        }
    }

    record Resources(CapturedBackup capturedBackup) implements AutoCloseable {
        Resources {
            Objects.requireNonNull(capturedBackup, "capturedBackup");
        }

        @Override
        public void close() throws IOException {
            capturedBackup.close();
        }
    }
}
