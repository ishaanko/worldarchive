package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One kind of copy, Git or ZIP, as the recovery operations use it. Every method blocks on the
 * calling worker thread; the copy passed in is the record's durable destination of this kind.
 */
interface RecoveryDestination {
    DestinationType type();

    /** Checks the copy in full; damage is a failed outcome, a copy that cannot be read throws. */
    VerificationOutcome verify(BackupRecord record, DestinationResult copy) throws Exception;

    /**
     * Writes the backup's world into an empty staging folder, checking every file against the
     * record's manifest; throws, with a reason the player can read, when this copy cannot.
     */
    void restore(BackupRecord record, DestinationResult copy, Path emptyStaging) throws Exception;

    /**
     * Deletes the copies of several backups of one world, together where the storage allows. Each
     * backup gets a result: SUCCESS when its copy is gone, FAILED with the reason when it was kept.
     * An interrupt does not stop the delete; the results always say what happened.
     */
    Map<BackupId, DestinationResult> delete(WorldId worldId, List<BackupRecord> records);
}
