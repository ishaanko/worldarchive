package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LiveBackupSaveGateTest {
    @Test
    void requestedSaveConsumesOnlyOwnedArmedFlushAndForceEvent() {
        LiveBackupSaveGate<Object, Object> gate = new LiveBackupSaveGate<>();
        Object server = new Object();
        Object otherServer = new Object();
        Object requested = new Object();

        assertTrue(gate.queueRequested(server, requested));
        assertTrue(gate.observeRequestedSave(server, true, true).isEmpty());
        assertTrue(gate.armRequested(server, requested));
        assertTrue(gate.observeRequestedSave(otherServer, true, true).isEmpty());
        assertTrue(gate.observeRequestedSave(server, false, true).isEmpty());
        assertTrue(gate.observeRequestedSave(server, true, false).isEmpty());
        assertSame(requested, gate.observeRequestedSave(server, true, true).orElseThrow());
        assertFalse(gate.hasPending());
    }

    @Test
    void exitSaveWaitsForServerStopAndDispatchesExactlyOnce() {
        LiveBackupSaveGate<Object, Object> gate = new LiveBackupSaveGate<>();
        Object server = new Object();
        Object exit = new Object();

        LiveBackupSaveGate.ExitInstall<Object> installed = gate.installExit(server, exit);
        assertTrue(installed.installed());
        assertTrue(installed.displaced().isEmpty());
        assertFalse(gate.installExit(server, new Object()).installed());
        assertTrue(gate.observeExitSave(server, true, false));
        assertTrue(gate.observeExitSave(server, true, false));
        assertTrue(gate.hasPending());

        LiveBackupSaveGate.StopResult<Object> stopped = gate.stop(server);
        assertEquals(LiveBackupSaveGate.StopKind.EXIT_READY, stopped.kind());
        assertSame(exit, stopped.value().orElseThrow());
        assertEquals(LiveBackupSaveGate.StopKind.NONE, gate.stop(server).kind());
        assertFalse(gate.hasPending());
    }

    @Test
    void exitWithoutFinalSaveFailsClosedAtServerStop() {
        LiveBackupSaveGate<Object, Object> gate = new LiveBackupSaveGate<>();
        Object server = new Object();
        Object exit = new Object();

        assertTrue(gate.installExit(server, exit).installed());
        LiveBackupSaveGate.StopResult<Object> stopped = gate.stop(server);

        assertEquals(LiveBackupSaveGate.StopKind.EXIT_MISSING_SAVE, stopped.kind());
        assertSame(exit, stopped.value().orElseThrow());
        assertFalse(gate.hasPending());
    }

    @Test
    void mismatchedFinalSavesRemainMissingAndFailClosed() {
        for (SaveEvent event : new SaveEvent[] {
            new SaveEvent(false, false),
            new SaveEvent(true, true)
        }) {
            LiveBackupSaveGate<Object, Object> gate = new LiveBackupSaveGate<>();
            Object server = new Object();
            Object exit = new Object();

            assertTrue(gate.installExit(server, exit).installed());
            assertFalse(gate.observeExitSave(
                    server, event.flush(), event.force()));
            LiveBackupSaveGate.StopResult<Object> stopped = gate.stop(server);
            assertEquals(LiveBackupSaveGate.StopKind.EXIT_MISSING_SAVE, stopped.kind());
            assertSame(exit, stopped.value().orElseThrow());
        }
    }

    @Test
    void newServerCallbacksCannotStealOrClearOldExit() {
        LiveBackupSaveGate<Object, Object> gate = new LiveBackupSaveGate<>();
        Object oldServer = new Object();
        Object newServer = new Object();
        Object oldExit = new Object();

        assertTrue(gate.installExit(oldServer, oldExit).installed());
        assertFalse(gate.installExit(newServer, new Object()).installed());
        assertFalse(gate.observeExitSave(newServer, true, false));
        assertEquals(LiveBackupSaveGate.StopKind.NONE, gate.stop(newServer).kind());
        assertTrue(gate.ownedBy(oldServer));

        assertTrue(gate.observeExitSave(oldServer, true, false));
        LiveBackupSaveGate.StopResult<Object> stopped = gate.stop(oldServer);
        assertEquals(LiveBackupSaveGate.StopKind.EXIT_READY, stopped.kind());
        assertSame(oldExit, stopped.value().orElseThrow());
    }

    @Test
    void sameServerExitReplacesRequestedSave() {
        for (String trigger : new String[] {"manual", "scheduled"}) {
            LiveBackupSaveGate<Object, String> gate = new LiveBackupSaveGate<>();
            Object server = new Object();

            assertTrue(gate.queueRequested(server, trigger));
            LiveBackupSaveGate.ExitInstall<String> installed = gate.installExit(server, "exit");
            assertTrue(installed.installed());
            assertSame(trigger, installed.displaced().orElseThrow());
            assertFalse(gate.armRequested(server, trigger));
            assertTrue(gate.observeExitSave(server, true, false));
            assertSame("exit", gate.stop(server).value().orElseThrow());
        }
    }

    @Test
    void requestedSaveIsReportedAbandonedAtOwnedServerStop() {
        LiveBackupSaveGate<Object, Object> gate = new LiveBackupSaveGate<>();
        Object server = new Object();
        Object requested = new Object();

        assertTrue(gate.queueRequested(server, requested));
        LiveBackupSaveGate.StopResult<Object> stopped = gate.stop(server);

        assertEquals(LiveBackupSaveGate.StopKind.REQUEST_ABANDONED, stopped.kind());
        assertSame(requested, stopped.value().orElseThrow());
    }

    private record SaveEvent(boolean flush, boolean force) {
    }
}
