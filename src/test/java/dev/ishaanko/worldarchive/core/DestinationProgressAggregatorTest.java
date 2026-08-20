package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DestinationProgressAggregatorTest {
    private static final OperationId OPERATION_ID = OperationId.create();

    private static final WorldId WORLD_ID = WorldId.create();

    private static final BackupId BACKUP_ID = BackupId.create();

    @Test
    void combinesInterleavedDestinationsOnOneScale() {
        DestinationProgressAggregator aggregator = new DestinationProgressAggregator(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                1_000);

        assertTrue(aggregator.aggregates());
        assertEquals(1_000, aggregator.totalUnits());
        assertEquals(250, aggregator.accept(
                DestinationType.ZIP,
                progress(OperationPhase.WRITING, 40, 80, "Writing ZIP backup")));
        assertEquals(500, aggregator.accept(
                DestinationType.GIT,
                progress(OperationPhase.READING, 3, 6, "Synchronizing Git snapshot")));
        assertEquals(750, aggregator.accept(
                DestinationType.ZIP,
                progress(OperationPhase.COMPLETE, 80, 80, "ZIP backup complete")));
        assertEquals(1_000, aggregator.accept(
                DestinationType.GIT,
                progress(OperationPhase.FAILED, 0, 6, "Git snapshot failed")));
    }

    @Test
    void neverReportsLessThanTheHighestReportedValue() {
        DestinationProgressAggregator aggregator = new DestinationProgressAggregator(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                1_000);

        assertEquals(400, aggregator.accept(
                DestinationType.ZIP,
                progress(OperationPhase.WRITING, 80, 100, "Writing ZIP backup")));
        assertEquals(400, aggregator.accept(
                DestinationType.ZIP,
                progress(OperationPhase.VERIFYING, 10, 100, "Verifying ZIP backup")));
        assertEquals(400, aggregator.accept(
                DestinationType.GIT,
                progress(OperationPhase.WRITING, 20, 100, "Synchronizing Git snapshot")));
        assertEquals(550, aggregator.accept(
                DestinationType.ZIP,
                progress(OperationPhase.VERIFYING, 90, 100, "Verifying ZIP backup")));
    }

    @Test
    void destinationWithoutAKnownTotalCountsAsNotStarted() {
        DestinationProgressAggregator aggregator = new DestinationProgressAggregator(
                List.of(DestinationType.GIT, DestinationType.ZIP),
                0);

        assertEquals(1_000, aggregator.totalUnits());
        assertEquals(0, aggregator.accept(
                DestinationType.GIT,
                progress(OperationPhase.PREPARING, 0, 0, "Preparing Git snapshot")));
        assertEquals(500, aggregator.accept(
                DestinationType.ZIP,
                progress(OperationPhase.COMPLETE, 0, 0, "ZIP backup complete")));
        assertEquals(1_000, aggregator.accept(
                DestinationType.GIT,
                progress(OperationPhase.COMPLETE, 0, 0, "Git snapshot complete")));
    }

    @Test
    void oneDestinationDoesNotAggregate() {
        DestinationProgressAggregator aggregator = new DestinationProgressAggregator(
                List.of(DestinationType.ZIP),
                800);

        assertFalse(aggregator.aggregates());
    }

    private static OperationProgress progress(
            OperationPhase phase,
            long completed,
            long total,
            String message) {
        return new OperationProgress(
                OPERATION_ID,
                WORLD_ID,
                Optional.of(BACKUP_ID),
                BackupOperation.CREATE,
                phase,
                completed,
                total,
                message);
    }
}
