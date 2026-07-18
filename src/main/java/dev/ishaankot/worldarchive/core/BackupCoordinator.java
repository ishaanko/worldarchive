package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Optional;

/**
 * Serialized lifecycle-facing backup service.
 *
 * <p>Implementations coalesce compatible concurrent triggers for the same world, serialize
 * incompatible operations, and allow different worlds to proceed independently.</p>
 */
public interface BackupCoordinator extends BackupService {
    Optional<OperationProgress> currentOperation(WorldId worldId);

    default boolean isBusy(WorldId worldId) {
        return currentOperation(worldId).isPresent();
    }
}
