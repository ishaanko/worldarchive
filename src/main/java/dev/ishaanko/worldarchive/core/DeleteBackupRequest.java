package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import java.util.Objects;

/** A deletion request carrying the exact confirmation token returned by prepareDelete. */
public record DeleteBackupRequest(BackupId backupId, OperationId confirmationToken) {
    public DeleteBackupRequest {
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(confirmationToken, "confirmationToken");
    }
}
