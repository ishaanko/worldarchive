package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.OperationId;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A world capture made by {@link SerializedBackupCoordinator#prepareCapture}. The caller hands it
 * to {@link SerializedBackupCoordinator#createPreparedBackup}, which takes it over, or closes it,
 * which deletes the capture. Until then the world counts as busy.
 */
public final class PreparedBackup implements AutoCloseable {
    private final SerializedBackupCoordinator owner;

    private final CreateBackupRequest request;

    private final OperationId operationId;

    private final AtomicReference<CapturedBackup> capture;

    private final Runnable onClose;

    PreparedBackup(
            SerializedBackupCoordinator owner,
            CreateBackupRequest request,
            OperationId operationId,
            CapturedBackup capture,
            Runnable onClose) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.request = Objects.requireNonNull(request, "request");
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.capture = new AtomicReference<>(Objects.requireNonNull(capture, "capture"));
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    public CreateBackupRequest request() {
        return request;
    }

    SerializedBackupCoordinator owner() {
        return owner;
    }

    OperationId operationId() {
        return operationId;
    }

    /** Takes the capture over; the new owner also ends the world's busy state. */
    CapturedBackup claim() {
        CapturedBackup claimed = capture.getAndSet(null);
        if (claimed == null) {
            throw new IllegalStateException("The prepared capture was already used or closed");
        }
        return claimed;
    }

    /** Deletes the capture unless it was handed on; closing again does nothing. */
    @Override
    public void close() throws IOException {
        CapturedBackup unclaimed = capture.getAndSet(null);
        if (unclaimed != null) {
            try {
                unclaimed.close();
            } finally {
                onClose.run();
            }
        }
    }
}
