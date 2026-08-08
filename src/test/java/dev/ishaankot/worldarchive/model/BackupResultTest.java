package dev.ishaankot.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BackupResultTest {
    @Test
    void preservesPartialDestinationSuccess() {
        BackupId backupId = BackupId.create();
        WorldId worldId = WorldId.create();
        DestinationResult git = DestinationResult.success(DestinationType.GIT, "refs/worldarchive/world/backup");
        DestinationResult zip = DestinationResult.failed(DestinationType.ZIP, "Destination is unavailable");

        BackupResult result = BackupResult.aggregate(
                backupId,
                worldId,
                List.of(git, zip),
                Instant.parse("2026-07-17T12:00:00Z"));

        assertEquals(BackupStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(List.of(git, zip), result.destinations());
    }

    @Test
    void computesAllTerminalAggregateStates() {
        assertEquals(BackupStatus.SUCCESS, BackupResult.aggregateStatus(List.of(
                DestinationResult.success(DestinationType.GIT, "ref"),
                DestinationResult.skipped(DestinationType.ZIP, "unchanged"))));
        assertEquals(BackupStatus.FAILED, BackupResult.aggregateStatus(List.of(
                DestinationResult.failed(DestinationType.GIT, "missing Git LFS"))));
        assertEquals(BackupStatus.SKIPPED, BackupResult.aggregateStatus(List.of()));
        assertEquals(BackupStatus.PARTIAL_SUCCESS, BackupResult.aggregateStatus(List.of(
                DestinationResult.pendingSync(DestinationType.GIT, "ref", "Remote unavailable"))));
    }

    @Test
    void destinationResultsAreDefensivelyCopiedAndUnique() {
        List<DestinationResult> mutable = new ArrayList<>();
        mutable.add(DestinationResult.success(DestinationType.GIT, "ref"));
        BackupResult result = BackupResult.aggregate(
                BackupId.create(),
                WorldId.create(),
                mutable,
                Instant.now());
        mutable.clear();
        assertEquals(1, result.destinations().size());
        assertThrows(UnsupportedOperationException.class, () -> result.destinations().clear());
        assertThrows(IllegalArgumentException.class, () -> BackupResult.aggregate(
                BackupId.create(),
                WorldId.create(),
                List.of(
                        DestinationResult.success(DestinationType.GIT, "one"),
                        DestinationResult.success(DestinationType.GIT, "two")),
                Instant.now()));
    }
}
