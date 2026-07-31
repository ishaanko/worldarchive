package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class OperationProgressTest {
    @Test
    void reportsKnownAndUnknownFractions() {
        OperationProgress known = new OperationProgress(
                OperationId.create(),
                WorldId.create(),
                Optional.empty(),
                BackupOperation.CREATE,
                OperationPhase.WRITING,
                25,
                100,
                "Writing");
        OperationProgress unknown = new OperationProgress(
                OperationId.create(),
                WorldId.create(),
                Optional.empty(),
                BackupOperation.CREATE,
                OperationPhase.READING,
                25,
                0,
                "Scanning");

        assertEquals(0.25, known.fraction().orElseThrow());
        assertTrue(unknown.fraction().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new OperationProgress(
                OperationId.create(),
                WorldId.create(),
                Optional.empty(),
                BackupOperation.CREATE,
                OperationPhase.WRITING,
                101,
                100,
                "Invalid"));
    }
}
