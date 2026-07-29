package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Short-lived, fingerprint-bound preview for an explicit cleanup confirmation. */
public record CleanupPlan(
        OperationId confirmationToken,
        WorldId worldId,
        Instant expiresAt,
        long currentBytes,
        long budgetBytes,
        long targetBytes,
        List<CleanupItem> items,
        Set<BackupId> protectedBackups,
        BackupId verifiedSafetyFloor,
        boolean targetReachable,
        String fingerprint) {
    public CleanupPlan {
        Objects.requireNonNull(confirmationToken, "confirmationToken");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (currentBytes < 0 || budgetBytes < 0 || targetBytes < 0) {
            throw new IllegalArgumentException("Cleanup plan sizes must not be negative");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        protectedBackups = Set.copyOf(Objects.requireNonNull(protectedBackups, "protectedBackups"));
        Objects.requireNonNull(verifiedSafetyFloor, "verifiedSafetyFloor");
        if (!protectedBackups.contains(verifiedSafetyFloor)) {
            throw new IllegalArgumentException("Verified safety floor must be protected");
        }
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.isBlank()) {
            throw new IllegalArgumentException("Cleanup fingerprint must not be blank");
        }
    }

    public long estimatedReclaimableBytes() {
        long total = 0;
        for (CleanupItem item : items) {
            total = Math.addExact(total, item.estimatedReclaimableBytes());
        }
        return total;
    }
}
