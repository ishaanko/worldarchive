package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.model.BackupId;
import java.util.Objects;
import java.util.Set;

/** Exact selected subset of a prepared cleanup preview. */
public record CleanupRequest(
        OperationId confirmationToken,
        Set<BackupId> selectedBackups) {
    public CleanupRequest {
        Objects.requireNonNull(confirmationToken, "confirmationToken");
        selectedBackups = Set.copyOf(Objects.requireNonNull(selectedBackups, "selectedBackups"));
        if (selectedBackups.isEmpty()) {
            throw new IllegalArgumentException("Cleanup selection must not be empty");
        }
    }
}
