package dev.ishaanko.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ConfigurationGateTest {
    /** Neither side waits: the render and server threads call the gate, and a wait there freezes the game. */
    @Test
    void workAndAFolderChangeRefuseEachOtherAtOnce() {
        ConfigurationGate gate = new ConfigurationGate(() -> { });

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ConfigurationGate.Permit backup = gate.enterWork();
            assertThrows(IllegalStateException.class, gate::enterFolderChange);
            backup.close();

            ConfigurationGate.Permit change = gate.enterFolderChange();
            assertThrows(IllegalStateException.class, gate::enterWork);
            // The first catalog scan of the new folders belongs to the change itself.
            ConfigurationGate.Permit scan = gate.enterSettingsWork();
            change.close();
            assertThrows(IllegalStateException.class, gate::enterFolderChange);
            scan.close();
            gate.enterFolderChange().close();
        });
    }

    @Test
    void theLastPermitRunsTheIdleCallbackAndEndsTheQuitWait() throws InterruptedException {
        AtomicInteger idle = new AtomicInteger();
        ConfigurationGate gate = new ConfigurationGate(idle::incrementAndGet);
        ConfigurationGate.Permit first = gate.enterWork();
        ConfigurationGate.Permit second = gate.enterWork();

        first.close();
        first.close();
        assertEquals(0, idle.get());
        assertFalse(gate.awaitIdle(Duration.ofMillis(50)));
        Thread.startVirtualThread(second::close);

        assertTrue(gate.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(1, idle.get());
    }
}
