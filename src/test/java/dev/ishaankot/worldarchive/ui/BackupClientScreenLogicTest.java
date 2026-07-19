package dev.ishaankot.worldarchive.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector2ic;
import org.junit.jupiter.api.Test;

final class BackupClientScreenLogicTest {
    @Test
    void backupRowTooltipIsClampedInsideTheWindow() {
        Vector2ic above = BackupRowButton.boundedTooltipPosition(
                428,
                256,
                400,
                240,
                170,
                120);
        assertEquals(254, above.x());
        assertEquals(108, above.y());

        Vector2ic below = BackupRowButton.boundedTooltipPosition(
                428,
                256,
                8,
                8,
                170,
                120);
        assertEquals(20, below.x());
        assertEquals(20, below.y());

        Vector2ic topRow = BackupRowButton.boundedTooltipPosition(
                428,
                256,
                200,
                70,
                170,
                120);
        assertEquals(212, topRow.x());
        assertEquals(82, topRow.y());
        assertInside(topRow, 170, 120, 428, 256);
    }

    @Test
    void retryAdviceRecognizesBothPrivateCaptureMutationMessages() {
        assertTrue(BackupOperationScreen.captureChanged(
                "World file changed while its private capture was being created: level.dat"));
        assertTrue(BackupOperationScreen.captureChanged(
                "World file changed size while it was being captured: region/r.0.0.mca"));
        assertFalse(BackupOperationScreen.captureChanged(
                "Remote repository changed while the Git snapshot was being synchronized"));
    }

    private static void assertInside(
            Vector2ic position,
            int width,
            int height,
            int screenWidth,
            int screenHeight) {
        assertTrue(position.x() >= 4);
        assertTrue(position.y() >= 4);
        assertTrue(position.x() + width <= screenWidth - 4);
        assertTrue(position.y() + height <= screenHeight - 4);
    }
}
