package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RuntimeBackupWorldsTest {
    @Test
    void hidesOnlyMissingWorldsWithoutBackups() {
        assertTrue(RuntimeBackupWorlds.shouldInclude(true, 0));
        assertTrue(RuntimeBackupWorlds.shouldInclude(false, 1));
        assertFalse(RuntimeBackupWorlds.shouldInclude(false, 0));
    }
}
