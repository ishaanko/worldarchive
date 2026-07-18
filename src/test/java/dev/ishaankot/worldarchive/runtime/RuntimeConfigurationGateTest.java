package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RuntimeConfigurationGateTest {
    @Test
    void rejectsPathTransactionWhileBackupPublicationIsActive() {
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate();
        RuntimeConfigurationGate.Permit backup = gate.enterBackup();

        assertThrows(
                IllegalStateException.class,
                gate::tryEnterConfigurationChange);

        backup.close();
        RuntimeConfigurationGate.Permit configuration = assertDoesNotThrow(
                gate::tryEnterConfigurationChange);
        configuration.close();
    }

    @Test
    void permitsMayCloseOnAnAsyncCompletionThread() throws InterruptedException {
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate();
        RuntimeConfigurationGate.Permit backup = gate.enterBackup();

        Thread.startVirtualThread(backup::close).join();

        RuntimeConfigurationGate.Permit configuration = assertDoesNotThrow(
                gate::tryEnterConfigurationChange);
        configuration.close();
    }
}
