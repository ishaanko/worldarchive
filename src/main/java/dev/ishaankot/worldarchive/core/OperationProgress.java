package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Immutable progress event; a zero total means that the total is not yet known. */
public record OperationProgress(
        OperationId operationId,
        WorldId worldId,
        Optional<BackupId> backupId,
        BackupOperation operation,
        OperationPhase phase,
        long completedUnits,
        long totalUnits,
        String message) {
    private static final int MAXIMUM_MESSAGE_LENGTH = 512;

    public OperationProgress {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(worldId, "worldId");
        backupId = Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(phase, "phase");
        if (completedUnits < 0 || totalUnits < 0) {
            throw new IllegalArgumentException("Progress units must not be negative");
        }
        if (totalUnits > 0 && completedUnits > totalUnits) {
            throw new IllegalArgumentException("Completed units must not exceed the known total");
        }
        Objects.requireNonNull(message, "message");
        if (message.length() > MAXIMUM_MESSAGE_LENGTH
                || message.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Progress message is too long or contains control characters");
        }
    }

    public OptionalDouble fraction() {
        if (totalUnits == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) completedUnits / totalUnits);
    }
}
