package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LiveBackupSaveGateTest {
    @Test
    void requestedSaveConsumesOnlyArmedFlushAndForceEvent() {
        LiveBackupSaveGate<Object> gate = new LiveBackupSaveGate<>();
        Object requested = new Object();

        assertTrue(gate.queueRequested(requested));
        assertTrue(gate.consume(requested, false, true, true).isEmpty());
        assertTrue(gate.armRequested(requested));
        assertTrue(gate.consume(requested, false, false, false).isEmpty());
        assertTrue(gate.consume(requested, true, true, false).isEmpty());
        assertSame(requested, gate.consume(requested, false, true, true).orElseThrow());
        assertFalse(gate.hasPending());
    }

    @Test
    void exitSaveConsumesOnlyFinalStoppingSaveEvent() {
        LiveBackupSaveGate<Object> gate = new LiveBackupSaveGate<>();
        Object exit = new Object();

        assertTrue(gate.replaceWithExit(exit).isEmpty());
        assertTrue(gate.consume(exit, false, true, false).isEmpty());
        assertTrue(gate.consume(exit, true, true, true).isEmpty());
        assertSame(exit, gate.consume(exit, true, true, false).orElseThrow());
        assertFalse(gate.hasPending());
    }

    @Test
    void exitReplacesEitherManualOrScheduledPendingSave() {
        for (String trigger : new String[] {"manual", "scheduled"}) {
            LiveBackupSaveGate<String> gate = new LiveBackupSaveGate<>();

            assertTrue(gate.queueRequested(trigger));
            assertSame(trigger, gate.replaceWithExit("exit").orElseThrow());
            assertFalse(gate.armRequested(trigger));
            assertSame("exit", gate.consume("exit", true, true, false).orElseThrow());
        }
    }
}
