package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.Optional;

/** Blocking worker-thread adapter for the independent destination implementations. */
interface RecoveryDestination {
    DestinationType destinationType();

    VerificationOutcome verify(BackupRecord record, DestinationResult destination) throws Exception;

    void materialize(BackupRecord record, DestinationResult destination, Path emptyTarget)
            throws Exception;

    boolean delete(BackupRecord record, DestinationResult destination) throws Exception;

    DestinationResult sync(BackupRecord record, DestinationResult destination) throws Exception;

    DestinationHealth health(Optional<WorldId> worldId) throws Exception;
}
